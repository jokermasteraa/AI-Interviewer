package com.axle.service.RAG;

import com.axle.bo.AnswerBO;
import com.axle.bo.SubmitAnswerBO;
import com.axle.pojo.Candidate;
import com.axle.pojo.InterviewRecord;
import com.axle.pojo.Job;
import com.axle.service.CandidateService;
import com.axle.service.InterviewRecordService;
import com.axle.service.JobService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
// ================ M2 版本的正确 Imports ================
import org.springframework.ai.chat.model.ChatModel; // M2: 注入 ChatModel
import org.springframework.ai.chat.prompt.Prompt;
// =======================================================
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class  ChatGLMService {

    // --- 1. 注入 Spring AI M2 客户端 ---
    private final ChatModel chatModel;
    // M2 版本注入 ChatModel
    private final VectorStore vectorStore;

    // --- 2. 注入您现有的业务服务 ---
    @Resource
    private JobService jobService;
    @Resource
    private InterviewRecordService interviewRecordService;
    @Resource
    private CandidateService candidateService;

    // M2: 通过构造函数注入 ChatModel 和 VectorStore
    public ChatGLMService(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    /**
     * 对提交的面试答案进行AI分析 (使用 Spring AI M2 重构)
     */
    public void analyze(SubmitAnswerBO submitAnswerBO) throws Exception {
        String candidateId = submitAnswerBO.getCandidateId();
        String jobId = submitAnswerBO.getJobId();

        // 为此次面试的 RAG 上下文创建唯一的文档 ID
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
            String jdText = "职位描述: " + job.getJobDesc();
            Document jdDoc = new Document(docIdJd, jdText, metadata);
            documents.add(jdDoc);
        }
        if (candidate.getRemark() != null && !candidate.getRemark().isEmpty()) {
            String resumeText = "简历内容: " + candidate.getRemark();
            Document resumeDoc = new Document(docIdResume, resumeText, metadata);
            documents.add(resumeDoc);
        }

        // VectorStore 会自动使用我们的 EmbeddingService (因为它现在是 Primary EmbeddingModel)
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            log.info("RAG: 瞬时上下文已添加到 Redis 向量库。 ({} docs)", documents.size());
        }

        // 准备查询
        StringBuilder conversationBuilder = new StringBuilder();
        for (AnswerBO answer : submitAnswerBO.getQuestionAnswerList()) {
            conversationBuilder.append("提问：").append(answer.getQuestion()).append("\n");
            conversationBuilder.append("回答：").append(answer.getAnswerContent()).append("\n\n");
        }
        String conversation = conversationBuilder.toString();

        // RAG 搜索
        String filterExpression = "candidateId == '" + candidateId + "' && jobId == '" + jobId + "'";

        SearchRequest searchRequest = SearchRequest.query(conversation)
                .withTopK(2)
                .withFilterExpression(filterExpression);

        List<Document> contextDocs = vectorStore.similaritySearch(searchRequest);
        String retrievedContext = contextDocs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n- "));

        log.debug("【RAG】检索到的上下文: {}", retrievedContext);

        // 构建 Prompt 并调用 AI
        String dynamicPrompt = buildDynamicPrompt(retrievedContext, conversation);
        Prompt prompt = new Prompt(dynamicPrompt);

        // M2: 直接调用 chatModel.call
        String rawJsonFromAI = chatModel.call(prompt).getResult().getOutput().getContent();

        // --- 6. 保存结果 (此方法无需修改) ---
        saveAnalysisResult(submitAnswerBO, conversation, rawJsonFromAI);

        // --- 7. 【重要】清理瞬时数据 ---
        List<String> docIdsToDelete = List.of(docIdJd, docIdResume);
        vectorStore.delete(docIdsToDelete);
        log.info("RAG: 瞬时上下文已从 Redis 向量库清除。");
    }


    /**
     * 保存分析结果到数据库 (此方法无需修改)
     */
    private void saveAnalysisResult(SubmitAnswerBO bo, String conversation, String rawJsonFromAI) {
        try {
            String cleanedJson = rawJsonFromAI;
            int firstBrace = cleanedJson.indexOf('{');
            int lastBrace = cleanedJson.lastIndexOf('}');

            if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                cleanedJson = cleanedJson.substring(firstBrace, lastBrace + 1);
            }
            cleanedJson = cleanedJson.trim();

            Job job = jobService.getDetail(bo.getJobId());
            InterviewRecord record = new InterviewRecord();
            record.setCandidateId(bo.getCandidateId());
            record.setAnswerContent(conversation);
            record.setResult(cleanedJson);
            record.setJobName(job.getJobName());
            record.setTakeTime(bo.getTotalSeconds());
            record.setCreateTime(LocalDateTime.now());
            record.setUpdatedTime(LocalDateTime.now());

            interviewRecordService.save(record);
            log.info("AI分析结果已成功保存到数据库，候选人ID: {}", bo.getCandidateId());

        } catch (Exception e) {
            log.error("解析或保存AI评估结果失败", e);
        }
    }


    /**
     * 【最终版 - 思维链Prompt】 (此方法无需修改)
     */
    private String buildDynamicPrompt(String context, String conversation) {
        String jdContext = "未检索到相关的职位描述信息。";
        String resumeContext = "未检索到相关的简历信息。";

        if (context != null && !context.isEmpty()) {
            String[] snippets = context.split("\n- ");
            for (String snippet : snippets) {
                if (snippet.startsWith("职位描述:")) {
                    jdContext = snippet.substring("职位描述:".length()).trim();
                } else if (snippet.startsWith("简历内容:")) {
                    resumeContext = snippet.substring("简历内容:".length()).trim();
                }
            }
        }

        return String.format(
                // ... 您的 Prompt 字符串 ...
                // (您的 Prompt 字符串无需修改)
                "### 角色\n" +
                        "你是一名逻辑严谨、要求苛刻的Java技术面试官。\n\n" +
                        "### 背景资料\n" +
                        "1. **评估标准 (源自JD)**: 以下是本次招聘的核心要求。\n" +
                        "   ```\n" +
                        "   %s\n" +
                        "   ```\n" +
                        "2. **候选人自述 (源自简历)**: 以下是候选人声称自己拥有的经验。\n" +
                        "   ```\n" +
                        "   %s\n" +
                        "   ```\n" +
                        "3. **候选人面试表现 (对话实录)**: 以下是候选人在面试中的实际回答。\n" +
                        "   ```\n" +
                        "   %s\n" +
                        "   ```\n\n" +
                        "### 你的思考步骤 (思维链)\n" +
                        "请严格按照以下步骤进行思考，并最终形成结论：\n" +
                        "1. **分析标准**：首先，仔细阅读【评估标准】，理解每一个技术要求的重要性。\n" +
                        "2. **分析表现**：然后，仔细阅读【候选人面试表现】，并与【候选人自述】进行交叉验证。\n" +
                        "3. **进行对比**：最后，将候选人的实际表现与评估标准进行**逐一严格对比**，判断其在每个维度上的符合程度。**这是你打分和评语的唯一依据。**\n\n" +
                        "### 输出格式要求\n" +
                        "你的最终输出**必须且只能是**一个从`{`开始，到`}`结束的、不含任何注释或额外标记的、结构完美的JSON对象。JSON结构如下：\n" +
                        "{\n" +
                        "  \"scores\": {\n" +
                        "    \"technical_ability\": [数字得分],\n" +
                        "    \"project_experience\": [数字得分],\n" +
                        "    \"logical_thinking\": [数字得分]\n" +
                        "  },\n" +
                        "  \"overall_evaluation\": {\n" +
                        "    \"summary\": \"[一句话综合总评]\",\n" +
                        "    \"highlights\": [\n" +
                        "      \"[第一个亮点]\"\n" +
                        "    ],\n" +
                        "    \"weaknesses\": [\n" +
                        "      \"[第一个不足]\"\n" +
                        "    ]\n" +
                        "  }\n" +
                        "}",
                jdContext,
                resumeContext,
                conversation
        );
    }
}