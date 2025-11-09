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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LiveInterviewServiceImplV1 extends BaseInfoProperties implements LiveInterviewService {

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

    // 定义Redis Key
    private String getHistoryKey(String cid) { return "live:history:" + cid; }
    private String getJdKey(String cid) { return "live:jd:" + cid; }
    private String getResumeKey(String cid) { return "live:resume:" + cid; }
    private String getJobNameKey(String cid) { return "live:jobname:" + cid; }


    @Override
    public String startInterview(String candidateId) {
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
        Instant start = Instant.now();
        String firstQuestion = chatModel.call(prompt).getResult().getOutput().getText();
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        log.info("生成第一个问题。总耗时: {} 秒 ({} 毫秒)",
                duration.toSeconds(),
                duration.toMillis());
        NextQuestionResponse response;

        // 6. 存入对话历史
        redis.rpush(getHistoryKey(candidateId), "AI: " + firstQuestion);

        return firstQuestion;
    }

    @Override
    public NextQuestionResponse postAnswerAndGetNext(String candidateId, String lastAnswer) {
        log.info("【一问一答】收到回答: {}", candidateId);

        // 1. 存入用户回答
        String historyKey = getHistoryKey(candidateId);
        redis.rpush(historyKey, "User: " + lastAnswer);

        // 2. 获取所有上下文
        String jdText = redis.get(getJdKey(candidateId));
        String resumeText = redis.get(getResumeKey(candidateId));
        List<String> historyList = redis.lrange(historyKey, 0, -1);
        String chatHistory = String.join("\n", historyList);

        // 3. 准备 "live-next-question.st" 模板
        org.springframework.core.io.Resource promptResource = resourceLoader.getResource("classpath:prompts/live-next-question.st");
        PromptTemplate promptTemplate = new PromptTemplate(promptResource);
        BeanOutputConverter<NextQuestionResponse> converter = new BeanOutputConverter<>(NextQuestionResponse.class);

        Prompt prompt = promptTemplate.create(Map.of(
                "jd_text", jdText,
                "resume_text", resumeText,
                "chat_history", chatHistory,
                "format", converter.getFormat()
        ));

        // 4. 调用AI
        Instant start = Instant.now();
        String rawJson = chatModel.call(prompt).getResult().getOutput().getText();
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        log.info("生成问题。总耗时: {} 秒 ({} 毫秒)",
                duration.toSeconds(),
                duration.toMillis());
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

    @Override
    public InterviewEvaluation generateSummary(String candidateId) {
        log.info("【一问一答】开始生成总结报告: {}", candidateId);

        // 1. 获取所有上下文
        String jdText = redis.get(getJdKey(candidateId));
        String resumeText = redis.get(getResumeKey(candidateId));
        String jobName = redis.get(getJobNameKey(candidateId));
        List<String> historyList = redis.lrange(getHistoryKey(candidateId), 0, -1);

        // 将逐题分析报告(Transcript) 替换为 完整的对话历史
        String conversation = historyList.stream()
                .map(line -> line.startsWith("AI:") ? "提问：" + line.substring(3) : "回答：" + line.substring(5))
                .collect(Collectors.joining("\n"));

        // 2. 使用您现有的 "interview-analysis-v2.st" 模板
        org.springframework.core.io.Resource promptResource = resourceLoader.getResource("classpath:prompts/live-summary.st");
        PromptTemplate promptTemplate = new PromptTemplate(promptResource);
        BeanOutputConverter<InterviewEvaluation> converter = new BeanOutputConverter<>(InterviewEvaluation.class);

        Map<String, Object> variables = Map.of(
                "jd_context", jdText,
                "resume_text", resumeText,
                "conversation", conversation, // 关键：输入是“完整对话历史”
                "format", converter.getFormat()
        );

        Prompt finalSummaryPrompt = promptTemplate.create(variables);

        // 3. 调用AI获取最终的JSON评估报告
        Instant start = Instant.now();
        String rawJsonFromAI = chatModel.call(finalSummaryPrompt).getResult().getOutput().getText();
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        log.info("生成总结报告。总耗时: {} 秒 ({} 毫秒)",
                duration.toSeconds(),
                duration.toMillis());
        InterviewEvaluation evaluation;
        try {
            evaluation = converter.convert(rawJsonFromAI);
            log.info("【一问一答】AI输出结构化成功。");
        } catch (Exception e) {
            log.error("【一问一答】AI输出结构化失败！AI原始返回: {}", rawJsonFromAI, e);
            throw new RuntimeException("AI 输出解析失败，请检查 Prompt 或 AI 模型状态", e);
        }

        // 4. 【重要】保存到数据库
        InterviewRecord record = new InterviewRecord();
        record.setCandidateId(candidateId);
        record.setAnswerContent(conversation); // 存对话历史
        record.setResult(JsonUtils.objectToJson(evaluation)); // 存JSON总结
        record.setJobName(jobName);
        record.setTakeTime(0); // "一问一答"模式无法简单计算时长，暂存0
        record.setCreateTime(LocalDateTime.now());
        record.setUpdatedTime(LocalDateTime.now());
        interviewRecordService.save(record);

        // 5. 清理Redis
        redis.del(getHistoryKey(candidateId));
        redis.del(getJdKey(candidateId));
        redis.del(getResumeKey(candidateId));
        redis.del(getJobNameKey(candidateId));

        return evaluation;
    }
}