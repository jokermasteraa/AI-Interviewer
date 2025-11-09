// 【已修正】ChatGLMService.java
package com.axle.service.RAG;

import com.axle.bo.AnswerBO;
import com.axle.bo.SubmitAnswerBO;
import com.axle.pojo.Candidate;
import com.axle.pojo.InterviewRecord;
import com.axle.pojo.Job;
import com.axle.service.CandidateService;
import com.axle.service.InterviewRecordService;
import com.axle.service.JobService;
import com.axle.utils.JsonUtils;
import com.axle.vo.ai.InterviewEvaluation;
import jakarta.annotation.Resource; // [正确] 这是注解，用于 @Resource
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;// [正确] 这是类，用于 'promptResource'
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatGLMService {

    // --- 1. 核心依赖 (不变) ---
    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    // --- 2. 业务服务 (不变) ---
    @Resource // [正确] 使用 jakarta.annotation.Resource 注解
    private JobService jobService;
    @Resource
    private InterviewRecordService interviewRecordService;
    @Resource
    private CandidateService candidateService;

    // --- 3. 新增的依赖 (不变) ---
    @Autowired
    private ResourceLoader resourceLoader;

    // 构造函数 (不变)
    public ChatGLMService(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    /**
     * 【已重构】使用 Spring AI 的 PromptTemplate 和 BeanOutputConverter
     */
    public void analyze(SubmitAnswerBO submitAnswerBO) throws Exception {
        String candidateId = submitAnswerBO.getCandidateId();
        String jobId = submitAnswerBO.getJobId();

        // --- 1. RAG - 准备JD和简历的瞬时上下文 (不变) ---
        String docIdJd = "interview:" + candidateId + ":" + jobId + ":jd";
        String docIdResume = "interview:" + candidateId + ":" + jobId + ":resume";

        Job job = jobService.getDetail(jobId);
        Candidate candidate = candidateService.getDetail(candidateId);

        Map<String, Object> metadata = Map.of(
                "candidateId", candidateId,
                "jobId", jobId,
                "type", "rag_context"
        );

        List<Document> documents = new ArrayList<>();
        if (job.getJobDesc() != null && !job.getJobDesc().isEmpty()) {
            documents.add(new Document(docIdJd, "职位描述: " + job.getJobDesc(), metadata));
        }
        if (candidate.getRemark() != null && !candidate.getRemark().isEmpty()) {
            documents.add(new Document(docIdResume, "简历内容: " + candidate.getRemark(), metadata));
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            log.info("RAG: 瞬时上下文已添加。 ({} docs)", documents.size());
        }

        // --- 2. RAG - 准备查询（面试对话） (不变) ---
        StringBuilder conversationBuilder = new StringBuilder();
        for (AnswerBO answer : submitAnswerBO.getQuestionAnswerList()) {
            conversationBuilder.append("提问：").append(answer.getQuestion()).append("\n");
            conversationBuilder.append("回答：").append(answer.getAnswerContent()).append("\n\n");
        }
        String conversation = conversationBuilder.toString();

        // --- 3. RAG - 检索 (不变, 但优化了上下文提取) ---
        String filterExpression = "candidateId == '" + candidateId + "' && jobId == '" + jobId + "'";
        SearchRequest searchRequest = SearchRequest.builder()
                .query(conversation)
                .topK(2)
                .filterExpression(filterExpression)
                .build();

        List<Document> contextDocs = vectorStore.similaritySearch(searchRequest);

        String jdContext = "未检索到相关的职位描述信息。";
        String resumeContext = "未检索到相关的简历信息。";

        for (Document doc : contextDocs) {
            if (doc.getText().startsWith("职位描述:")) {
                jdContext = doc.getText().substring("职位描述:".length()).trim();
            } else if (doc.getText().startsWith("简历内容:")) {
                resumeContext = doc.getText().substring("简历内容:".length()).trim();
            }
        }
        log.debug("【RAG】检索到的JD: {}", jdContext);
        log.debug("【RAG】检索到的Resume: {}", resumeContext);

        // --- 4. 核心重构：使用模板和结构化输出 (不变) ---
        BeanOutputConverter<InterviewEvaluation> converter =
                new BeanOutputConverter<>(InterviewEvaluation.class);

        // [正确] 使用 org.springframework.core.io.Resource 类
        Resource promptResource = (Resource) resourceLoader.getResource("classpath:prompts/interview-analysis-v2.st");
        PromptTemplate promptTemplate = new PromptTemplate((org.springframework.core.io.Resource) promptResource);

        Map<String, Object> variables = Map.of(
                "jd_context", jdContext,
                "resume_context", resumeContext,
                "conversation", conversation,
                "format", converter.getFormat()
        );

        Prompt prompt = promptTemplate.create(variables);

        // --- 5. RAG - 生成 (不变) ---
        String rawJsonFromAI = chatModel.call(prompt).getResult().getOutput().getText();

        InterviewEvaluation evaluation;
        try {
            evaluation = converter.convert(rawJsonFromAI);
        } catch (Exception e) {
            log.error("AI输出结构化失败！AI原始返回: {}", rawJsonFromAI, e);
            throw new RuntimeException("AI 输出解析失败，请检查 Prompt 或 AI 模型状态", e);
        }

        // --- 6. 保存结果 (不变) ---
        saveAnalysisResult(submitAnswerBO, conversation, evaluation);

        // --- 7. 清理 (不变) ---
        List<String> docIdsToDelete = List.of(docIdJd, docIdResume);
        vectorStore.delete(docIdsToDelete);
        log.info("RAG: 瞬时上下文已清除。");
    }


    /**
     * 【已重构】保存方法 (不变)
     */
    private void saveAnalysisResult(SubmitAnswerBO bo, String conversation, InterviewEvaluation evaluation) {
        try {
            // [正确] POJO -> 字符串
            String cleanedJson = JsonUtils.objectToJson(evaluation);

            // 创建数据库实体 (不变)
            Job job = jobService.getDetail(bo.getJobId());
            InterviewRecord record = new InterviewRecord();
            record.setCandidateId(bo.getCandidateId());
            record.setAnswerContent(conversation);
            record.setResult(cleanedJson);
            record.setJobName(job.getJobName());
            record.setTakeTime(bo.getTotalSeconds());
            record.setCreateTime(LocalDateTime.now());
            record.setUpdatedTime(LocalDateTime.now());

            // 保存到数据库 (不变)
            interviewRecordService.save(record);
            log.info("AI分析结果已成功保存到数据库，候选人ID: {}", bo.getCandidateId());

        } catch (Exception e) {
            log.error("保存AI评估结果失败", e);
        }
    }
}