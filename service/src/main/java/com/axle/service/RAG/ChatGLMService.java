package com.axle.service.RAG;

import com.axle.bo.AnswerBO;
import com.axle.bo.SubmitAnswerBO;
import com.axle.glm.ChatGLMModel;
import com.axle.glm.EventType;
import com.axle.glm.GLMResponseV3;
import com.axle.pojo.Candidate;
import com.axle.pojo.InterviewRecord;
import com.axle.pojo.Job;
import com.axle.service.CandidateService;
import com.axle.service.InterviewRecordService;
import com.axle.service.JobService;
import com.axle.service.RAG.VectorDBService;
import com.axle.utils.GLMTokenUtils;
import com.axle.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.internal.sse.RealEventSource;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class ChatGLMService {

    // --- 1. 将所有常量和配置从 ChatGLMTask 移到这里 ---
    public static final Integer connectTimeout = 600;
    public static final Integer writeTimeout = 500;
    public static final Integer readTimeout = 400;

    public static final String SSE_CONTENT_TYPE = "text/event-stream";
    public static final String DEFAULT_USER_AGENT = "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT; DigExt)";
    public static final String APPLICATION_JSON = "application/json";
    public static final String JSON_CONTENT_TYPE = APPLICATION_JSON + "; charset=utf-8";

    public static final String apiUrl = "https://open.bigmodel.cn/api/paas/v3/model-api/" + ChatGLMModel.CHATGLM_4_Flash.key + "/sse-invoke";

    // --- 2. 将所有依赖注入移到这里 ---
    @Resource
    private JobService jobService;
    @Resource
    private InterviewRecordService interviewRecordService;
    @Resource
    private VectorDBService vectorDBService;
    @Resource
    private EmbeddingService embeddingService;
    @Resource
    private CandidateService candidateService;

    // --- 3. 核心业务方法 (原display方法，改名为analyze) ---
    /**
     * 对提交的面试答案进行AI分析
     * @param submitAnswerBO 包含候选人ID、职位ID和答案列表的对象
     */

    public void analyze(SubmitAnswerBO submitAnswerBO) throws Exception {
        String candidateId = submitAnswerBO.getCandidateId();
        String jobId = submitAnswerBO.getJobId();
        String indexName = "interview-" + candidateId + "-" + jobId;

        // 【核心修正】將 createIndex 改為 createStore
        vectorDBService.createStore(indexName);

        // --- 後續的 addDocument 和 search 調用完全無需修改 ---
        Job job = jobService.getDetail(jobId);
        if (job.getJobDesc() != null && !job.getJobDesc().isEmpty()) {
            vectorDBService.addDocument(indexName, "职位描述: " + job.getJobDesc(), embeddingService.embed(job.getJobDesc()));
        }

        Candidate candidate = candidateService.getDetail(candidateId);
        if (candidate.getRemark() != null && !candidate.getRemark().isEmpty()) {
            vectorDBService.addDocument(indexName, "简历内容: " + candidate.getRemark(), embeddingService.embed(candidate.getRemark()));
        }

        // --- LangChain4j 不需要手動 buildIndex，這一步可以刪除 ---
        // vectorDBService.buildIndex(indexName);
        log.info("RAG: 上下文信息已添加到內存向量庫。");

        StringBuilder conversationBuilder = new StringBuilder();
        for (AnswerBO answer : submitAnswerBO.getQuestionAnswerList()) {
            conversationBuilder.append("提问：").append(answer.getQuestion()).append("\n");
            conversationBuilder.append("回答：").append(answer.getAnswerContent()).append("\n\n");
        }
        String conversation = conversationBuilder.toString();

        float[] queryVector = embeddingService.embed(conversation);
        List<String> contextSnippets = vectorDBService.search(indexName, queryVector, 2);
        String retrievedContext = String.join("\n- ", contextSnippets);

        String dynamicPrompt = buildDynamicPrompt(retrievedContext, conversation);
        log.debug("【RAG】最終提交給ChatGLM的動態Prompt: {}", dynamicPrompt);

        runSseChat(dynamicPrompt, submitAnswerBO, conversation);
    }

    // --- 4. SSE调用逻辑封装 (原ChatGLMTask中的核心网络请求代码) ---
    private void runSseChat(String prompt, SubmitAnswerBO submitAnswerBO, String conversation) throws InterruptedException {

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", GLMTokenUtils.generateToken())
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", SSE_CONTENT_TYPE)
                .post(RequestBody.create(MediaType.parse("application/json"), generateBodyString(prompt)))
                .build();

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .writeTimeout(writeTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .build();

        // 使用 AtomicReference 来累加流式返回的结果
        AtomicReference<String> finalResult = new AtomicReference<>("");
        // 使用 CountDownLatch 来等待SSE流结束
        CountDownLatch latch = new CountDownLatch(1);

        RealEventSource realEventSource = new RealEventSource(request, new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, @Nullable String id, @Nullable String type, String data) {
                GLMResponseV3 response = JsonUtils.jsonToPojo(data, GLMResponseV3.class);
                if (response != null && response.getData() != null) {
                    // 累加每次收到的数据片段
                    finalResult.getAndAccumulate(response.getData(), (current, update) -> current + update);
                }

                // 当收到结束信号时，保存最终结果到数据库
                if (EventType.finish.key.equals(type)) {
                    saveAnalysisResult(submitAnswerBO, conversation, finalResult.get());
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                log.info("ChatGLM SSE连接关闭，候选人ID: {}", submitAnswerBO.getCandidateId());
                latch.countDown();
            }

            @Override
            public void onFailure(EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
                log.error("ChatGLM SSE连接失败，候选人ID: {}", submitAnswerBO.getCandidateId(), t);
                latch.countDown();
            }
        });

        realEventSource.connect(okHttpClient);

        // 阻塞当前线程，等待SSE流处理完成，可以设置一个最长等待时间
        boolean finishedInTime = latch.await(5, TimeUnit.MINUTES);
        if (!finishedInTime) {
            log.warn("ChatGLM SSE调用超时，候选人ID: {}", submitAnswerBO.getCandidateId());
        }
    }

    // --- 5. 数据库保存逻辑 ---
// ChatGLMService.java

    private void saveAnalysisResult(SubmitAnswerBO bo, String conversation, String rawJsonFromAI) {
        try {
            // 【核心修正】在解析和保存前，先对原始字符串进行清理
            String cleanedJson = rawJsonFromAI;

            // 寻找第一个 '{' 和最后一个 '}'，截取出一个合法的JSON子串
            int firstBrace = cleanedJson.indexOf('{');
            int lastBrace = cleanedJson.lastIndexOf('}');

            if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                cleanedJson = cleanedJson.substring(firstBrace, lastBrace + 1);
            }
            cleanedJson = cleanedJson.trim(); // 去除可能存在的空白

            // 现在，我们使用清理后的 cleanedJson 进行后续操作
            // ... (后续的解析、打印日志、保存到数据库的逻辑不变) ...

            Job job = jobService.getDetail(bo.getJobId());
            InterviewRecord record = new InterviewRecord();
            record.setCandidateId(bo.getCandidateId());
            record.setAnswerContent(conversation);
            record.setResult(cleanedJson); // 【注意】保存的是清理后的纯净JSON
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

    // --- 6. 请求体构建工具方法 ---
    private String generateBodyString(String content) {
        String requestId = String.format("lee-%d", System.currentTimeMillis());
        List<Prompt> promptList = new ArrayList<>();
        promptList.add(new Prompt("user", content));

        Map<String, Object> paramsMap = new HashMap<>();
        paramsMap.put("request_id", requestId);
        paramsMap.put("prompt", promptList);
        paramsMap.put("incremental", true);
        paramsMap.put("temperature", 0.9f);
        paramsMap.put("top_p", 0.7f);
        paramsMap.put("sseFormat", "data");
        try {
            return new ObjectMapper().writeValueAsString(paramsMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // --- 7. 内部数据结构 (Prompt类) ---
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Prompt {
        private String role;
        private String content;
    }

    // 请将此方法添加到 ChatGLMService.java 中

    /**
     * 构建动态Prompt。
     * 该方法将检索到的上下文和原始对话内容，整合成一个结构化的、指令明确的Prompt。
     *
     * @param context      从向量数据库中检索出的、与对话相关的背景信息（简历、JD片段）。
     * @param conversation 原始的面试问答对话内容。
     * @return             一个精心设计、准备提交给大语言模型的最终Prompt字符串。
     */
    // ChatGLMService.java

    /**
     * 【最终版 - 思维链Prompt】
     * 引导AI进行分步思考，严格区分JD和候选人表现，并强制输出干净的JSON。
     */
    // ChatGLMService.java

    /**
     * 【最终版 - 思维链Prompt】
     * 引导AI进行分步思考，严格区分JD和候选人表现，并强制输出干净的JSON。
     */
    private String buildDynamicPrompt(String context, String conversation) {
        String jdContext = "未检索到相关的职位描述信息。";
        String resumeContext = "未检索到相关的简历信息。";

        String[] snippets = context.split("\n- ");
        for (String snippet : snippets) {
            if (snippet.startsWith("职位描述:")) {
                jdContext = snippet.substring("职位描述:".length()).trim();
            } else if (snippet.startsWith("简历内容:")) {
                resumeContext = snippet.substring("简历内容:".length()).trim();
            }
        }

        return String.format(
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