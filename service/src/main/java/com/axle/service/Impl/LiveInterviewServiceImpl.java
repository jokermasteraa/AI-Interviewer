package com.axle.service.Impl;

import com.axle.base.BaseInfoProperties;
import com.axle.bo.NextQuestionResponse;
import com.axle.pojo.Candidate;
import com.axle.pojo.InterviewRecord;
import com.axle.pojo.Job;
import com.axle.service.CandidateService;
import com.axle.service.InterviewRecordService;
import com.axle.service.JobService;
import com.axle.service.LiveInterviewService;
import com.axle.utils.JsonUtils;
import com.axle.vo.ai.InterviewEvaluation;
import lombok.extern.slf4j.Slf4j;
// --- 新增 Imports ---
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
// --- 结束 Imports ---
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LiveInterviewServiceImpl extends BaseInfoProperties implements LiveInterviewService {

    @Autowired
    private ChatModel chatModel;
    @Autowired
    private CandidateService candidateService;
    @Autowired
    private JobService jobService;
    @Autowired
    private InterviewRecordService interviewRecordService;
    @Autowired
    private ResourceLoader resourceLoader;
    
    // --- 【优化】注入 VectorStore ---
    @Autowired
    private VectorStore vectorStore;

    // ... (Redis Key 定义 和 startInterview, postAnswerAndGetNext 方法保持不变) ...
    private String getHistoryKey(String cid) { return "live:history:" + cid; }
    private String getJdKey(String cid) { return "live:jd:" + cid; }
    private String getResumeKey(String cid) { return "live:resume:" + cid; }
    private String getJobNameKey(String cid) { return "live:jobname:" + cid; }


    @Override
    public String startInterview(String candidateId) {
        // ... (此方法代码不变) ...
        log.info("【一问一答】开始面试: {}", candidateId);

        // 1. 清理旧的Redis缓存
        redis.del(getHistoryKey(candidateId));
        redis.del(getJdKey(candidateId));
        redis.del(getResumeKey(candidateId));
        redis.del(getJobNameKey(candidateId));

        // 2. 获取JD和Resume
        Candidate candidate = candidateService.getDetail(candidateId);
        Job job = jobService.getDetail(candidate.getJobId());
        String resumeText = (candidate.getRemark() != null) ? candidate.getRemark() : "未提供简历";
        String jdText = (job.getJobDesc() != null) ? job.getJobDesc() : "未提供职位描述";

        // 3. 缓存JD和Resume
        redis.set(getJdKey(candidateId), jdText);
        redis.set(getResumeKey(candidateId), resumeText);
        redis.set(getJobNameKey(candidateId), job.getJobName());

        // 4. 加载 "live-start-interview.st" 模板
        org.springframework.core.io.Resource promptResource = resourceLoader.getResource("classpath:prompts/live-start-interview.st");
        PromptTemplate promptTemplate = new PromptTemplate(promptResource);
        Prompt prompt = promptTemplate.create(Map.of(
                "jd_text", jdText,
                "resume_text", resumeText
        ));

        // 5. 调用AI获取第一个问题
        String firstQuestion = chatModel.call(prompt).getResult().getOutput().getText();

        // 6. 存入对话历史
        redis.rpush(getHistoryKey(candidateId), "AI: " + firstQuestion);

        return firstQuestion;
    }

    @Override
    public NextQuestionResponse postAnswerAndGetNext(String candidateId, String lastAnswer) {
        // ... (此方法代码不变) ...
        log.info("【一问一答】收到回答: {}", candidateId);
        
        // 1. 存入用户回答
        String historyKey = getHistoryKey(candidateId);
        redis.rpush(historyKey, "User: " + lastAnswer);

        // 2. 获取所有上下文
        String jdText = redis.get(getJdKey(candidateId));
        String resumeText = redis.get(getResumeKey(candidateId));
        List<String> historyList = redis.lrange(historyKey, 0, -1);
        String chatHistory = String.join("\n", historyList);

        // 3. 准备 "live-next-question.st" (V3版) 模板
        org.springframework.core.io.Resource promptResource = resourceLoader.getResource("classpath:prompts/live-next-question.st");
        PromptTemplate promptTemplate = new PromptTemplate(promptResource);
        BeanOutputConverter<NextQuestionResponse> converter = new BeanOutputConverter<>(NextQuestionResponse.class);
        
        Prompt prompt = promptTemplate.create(Map.of(
                "jd_text", jdText,
                "resume_text", resumeText,
                "chat_history", chatHistory,
                "format", converter.getFormat()
        ));

        // 4. 调用AI (使用 'glm-4-flash' 会更快)
        String rawJson = chatModel.call(prompt).getResult().getOutput().getText();
        NextQuestionResponse response;
        try {
            response = converter.convert(rawJson);
        } catch (Exception e) {
            log.error("【一问一答】AI输出解析失败: {}", rawJson, e);
            response = new NextQuestionResponse();
            response.setFinished(true); // 异常时直接结束
            response.setNextQuestion("面试出现异常，即将结束。");
        }

        // 5. 存入AI的新问题（如果没结束）
        if (!response.isFinished()) {
            redis.rpush(historyKey, "AI: " + response.getNextQuestion());
        } else {
            log.info("【一问一答】AI判断面试结束: {}", candidateId);
        }
        
        return response;
    }

    /**
     * 【V3版 - RAG优化】
     * 按照您的提议：
     * 1. 遍历聊天记录 (Q/A)。
     * 2. RAG 检索每个 Q 的 "评分标准"。
     * 3. AI "助教" 生成 "逐题分析报告"。
     * 4. AI "主考官" (interview-analysis-v2.st) 根据报告、JD、Resume 总结。
     */
    @Override
    public InterviewEvaluation generateSummary(String candidateId) {
        log.info("【一问一答 RAG总结】开始生成总结报告: {}", candidateId);

        // 1. 获取所有上下文
        String jdText = redis.get(getJdKey(candidateId));
        String resumeText = redis.get(getResumeKey(candidateId));
        String jobName = redis.get(getJobNameKey(candidateId));
        List<String> historyList = redis.lrange(getHistoryKey(candidateId), 0, -1);
        
        // 2. 【RAG 逐题分析】(复刻 ChatGLMService 的逻辑)
        StringBuilder perQuestionAnalysisReport = new StringBuilder();
        
        // 解析聊天记录为 Q/A 对
        List<Map<String, String>> qaPairs = new ArrayList<>();
        String currentQuestion = null;
        for (String line : historyList) {
            if (line.startsWith("AI: ")) {
                currentQuestion = line.substring(4);
            } else if (line.startsWith("User: ") && currentQuestion != null) {
                qaPairs.add(Map.of("question", currentQuestion, "answer", line.substring(6)));
                currentQuestion = null; // 重置
            }
        }

        for (Map<String, String> qa : qaPairs) {
            String questionText = qa.get("question");
            String candidateAnswerText = qa.get("answer");

            // 2.1. RAG检索：用“问题”去知识库检索“评分标准”
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(questionText)
                    .topK(1)
                    .filterExpression("doc_type == 'scoring_criteria'") //
                    .build();
            List<Document> contextDocs = vectorStore.similaritySearch(searchRequest);
            String retrievedCriteria = contextDocs.isEmpty()
                    ? "暂无官方评分标准，请基于你的通用知识进行评估。"
                    : contextDocs.get(0).getText(); // .getText() 包含了标准答案和评分点

            // 2.2. 构建“单题评估”Prompt (复用 ChatGLMService 的逻辑)
            String perQuestionPrompt = buildPerQuestionPrompt(questionText, candidateAnswerText, retrievedCriteria);

            // 2.3. 调用LLM获取单题评价 (非JSON，纯文本)
            // (注意: 这里可以用 'glm-4-flash' 提速)
            String perQuestionFeedback = chatModel.call(new Prompt(perQuestionPrompt)).getResult().getOutput().getText();

            // 2.4. 汇总逐题评价
            perQuestionAnalysisReport.append("--- 问题：").append(questionText).append(" ---\n");
            perQuestionAnalysisReport.append(perQuestionFeedback).append("\n\n");
        }
        
        log.info("【一问一答 RAG总结】逐题分析完成。准备生成最终报告...");
        
        // 3. 【最终总结】
        // 【关键】我们现在可以安全地复用您原始的、久经考验的 "interview-analysis-v2.st" 模板
        org.springframework.core.io.Resource promptResource = resourceLoader.getResource("classpath:prompts/interview-analysis-v2.st");
        PromptTemplate promptTemplate = new PromptTemplate(promptResource);
        BeanOutputConverter<InterviewEvaluation> converter = new BeanOutputConverter<>(InterviewEvaluation.class);

        Map<String, Object> variables = Map.of(
                "jd_context", jdText,
                "resume_context", resumeText,
                "conversation", perQuestionAnalysisReport.toString(), // 关键：输入是“逐题分析报告”
                "format", converter.getFormat()
        );

        Prompt finalSummaryPrompt = promptTemplate.create(variables);

        // 4. 调用LLM获取最终的JSON评估报告 (这里建议用强模型，比如 glm-4.5-airx 或 glm-4)
        // (注意: Spring AI 不支持单次调用切换模型，这里会使用您在 yml 中配置的 'glm-4-flash'，评估质量可能会略低，但速度快)
        String rawJsonFromAI = chatModel.call(finalSummaryPrompt).getResult().getOutput().getText();
        InterviewEvaluation evaluation;
        try {
            evaluation = converter.convert(rawJsonFromAI);
        } catch (Exception e) {
            log.error("【一问一答 RAG总结】AI输出结构化失败！AI原始返回: {}", rawJsonFromAI, e);
            throw new RuntimeException("AI 输出解析失败", e);
        }

        // 5. 保存到数据库
        InterviewRecord record = new InterviewRecord();
        record.setCandidateId(candidateId);
        record.setAnswerContent(historyList.stream().collect(Collectors.joining("\n"))); // 存原始聊天记录
        record.setResult(JsonUtils.objectToJson(evaluation)); // 存JSON总结
        record.setJobName(jobName);
        record.setTakeTime(0);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdatedTime(LocalDateTime.now());
        interviewRecordService.save(record);

        // 6. 清理Redis
        redis.del(getHistoryKey(candidateId));
        redis.del(getJdKey(candidateId));
        redis.del(getResumeKey(candidateId));
        redis.del(getJobNameKey(candidateId));
        
        return evaluation;
    }
    
    /**
     * 辅助方法：复用 ChatGLMService 中的单题评估Prompt
     */
    private String buildPerQuestionPrompt(String question, String candidateAnswer, String criteria) {
        return String.format(
                "### 角色\n" +
                        "你是一名严格的Java技术面试官，请对候选人的单个回答进行打分和评价。\n\n" +
                        "### 评估标准 (参考答案与评分点)\n" +
                        "```\n%s\n```\n\n" +
                        "### 面试内容\n" +
                        "**问题**：%s\n" +
                        "**候选人回答**：%s\n\n" +
                        "### 你的任务\n" +
                        "1.  **对比**：请严格按照【评估标准】来对比【候选人回答】。\n" +
                        "2.  **评价**：请给出针对这个回答的具体反馈，指出其优点和遗漏的知识点。\n" +
                        "3.  **格式**：请直接输出评价文字，不要使用JSON。\n\n" +
                        "### 评价：",
                criteria,
                question,
                candidateAnswer
        );
    }
}