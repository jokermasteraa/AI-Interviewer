// 【已重构】ChatGLMService.java
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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatGLMService {

    // --- 1. 核心依赖 ---
    private final ChatModel zhipuModel;
    private final ChatModel qwenModel;
    private final VectorStore vectorStore;

    // --- 2. 业务服务 ---
    @Resource
    private JobService jobService;
    @Resource
    private InterviewRecordService interviewRecordService;
    @Resource
    private CandidateService candidateService;

    // --- 3. 模板加载器 ---
    @Autowired
    private ResourceLoader resourceLoader; // 用于加载 .st 模板文件

    // --- 4.  缓存预加载的模板 ---
    private PromptTemplate finalSummaryTemplate;
    private PromptTemplate perQuestionTemplate;

    // 构造函数
    public ChatGLMService(@Qualifier("zhipuAiChatModel") ChatModel zhipuModel,
                          @Qualifier("dashscopeChatModel") ChatModel qwenModel, VectorStore vectorStore) {
        this.zhipuModel = zhipuModel;
        this.qwenModel = qwenModel;
        this.vectorStore = vectorStore;
    }


    @PostConstruct
    public void initTemplates(){
        log.info("【ChatGLMService】 正在预加载 Prompt 模板...");
        //1.加载单题模版
        org.springframework.core.io.Resource promptResource = resourceLoader.getResource("classpath:newprompts/singleanalysis.st");
        perQuestionTemplate = new PromptTemplate(promptResource);
        //2.加载总结模版
        promptResource = resourceLoader.getResource("classpath:newprompts/finalanalysis.st");
        finalSummaryTemplate = new PromptTemplate(promptResource);
    }

    // =================================================================================
    // RAG 目标 1: 回答评估与反馈生成 (核心业务)
    // =================================================================================
//    int corePoolSize = Runtime.getRuntime().availableProcessors(); // 16
//    int maxPoolSize = corePoolSize * 20; // 320
//    int queueCapacity = 1000; // 有界队列，防止内存溢出
//
//    ExecutorService executor = new ThreadPoolExecutor(
//            10,
//            10,
//            60L, TimeUnit.SECONDS,
//            new LinkedBlockingQueue<>(queueCapacity),
//            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：主线程执行
//    );

    ExecutorService executor = Executors.newFixedThreadPool(10);
    /**
     * 【已重构】使用 RAG 进行“逐题评估” + “总结汇报”
     */
    public void analyze(SubmitAnswerBO submitAnswerBO) throws Exception {

        log.info("【RAG 目标1: 评估反馈】开始分析候选人: {}", submitAnswerBO.getCandidateId());

        // --- 1. 逐题评估 (并行优化) ---
        List<AnswerBO> questionAnswerList = submitAnswerBO.getQuestionAnswerList();
        Instant fullStartTime = Instant.now();

        // 1.1 【优化】将串行循环改为并行流
        List<CompletableFuture<String>> analysisFutures = questionAnswerList.stream()
                .filter(answer -> answer.getAnswerContent() != null && !answer.getAnswerContent().trim().isEmpty())
                .map(answer ->
                        // 1.2 【优化】为每个问题的评估创建一个异步任务
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                Instant startTime = Instant.now();
                                String questionText = answer.getQuestion();
                                String candidateAnswerText = answer.getAnswerContent();

                                // 1.3 RAG检索
                                SearchRequest searchRequest = SearchRequest.builder()
                                        .query(questionText)
                                        .topK(1)
                                        .filterExpression("doc_type == 'scoring_criteria'")
                                        .build();
                                List<Document> contextDocs = vectorStore.similaritySearch(searchRequest);
                                String retrievedCriteria = contextDocs.isEmpty() ? "暂无官方评分标准，请基于你的通用知识进行评估。" : contextDocs.get(0).getText();

                                // 1.4 构建“单题评估”Prompt
//                                String perQuestionPrompt = buildPerQuestionPrompt(questionText, candidateAnswerText, retrievedCriteria);
                                Map<String, Object> variables = Map.of("question", questionText,
                                        "candidateAnswer", candidateAnswerText,
                                        "criteria", retrievedCriteria);

                               Prompt perQuestionPrompt = perQuestionTemplate.create(variables);

                                // 1.5 调用LLM获取单题评价
                                String perQuestionFeedback = qwenModel.call(perQuestionPrompt).getResult().getOutput().getText();

                                Instant endTime = Instant.now();
                                Duration duration = Duration.between(startTime,endTime);
                                log.info("【RAG 目标1: 评估反馈】单题评估耗时:{} 秒 {} 毫秒",duration.toSeconds(),duration.toMillis());

                                // 1.6 返回格式化好的单个报告
                                return "--- 问题：" + questionText + " ---\n" + perQuestionFeedback + "\n\n";

                            } catch (Exception e) {
                                log.error("并行评估单题失败: {}", answer.getQuestion(), e);
                                return "--- 问题：" + answer.getQuestion() + " ---\n" + "AI评估异常。\n\n";
                            }
                        })
                ).toList(); // 使用 .toList() (Java 16+) 或 .collect(Collectors.toList())

        // 1.7 【优化】等待所有并行的评估任务完成
        CompletableFuture.allOf(analysisFutures.toArray(new CompletableFuture[0])).join();

        // 1.8 【优化】汇总所有结果
        String perQuestionAnalysisReport = analysisFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.joining());

        log.info("【RAG 目标1】逐题评估完成。准备生成最终总结报告...");

            // --- 2. 最终总结 (使用你原来的逻辑，但输入变了) ---
            // 2.1. 获取JD和简历（作为最终总结的上下文，而不是RAG知识）
            Job job = jobService.getDetail(submitAnswerBO.getJobId());
            Candidate candidate = candidateService.getDetail(submitAnswerBO.getCandidateId());
            String jdContext = (job != null && job.getJobDesc() != null) ? job.getJobDesc() : "未提供职位描述。";
            String resumeContext = (candidate != null && candidate.getRemark() != null) ? candidate.getRemark() : "未提供简历信息。";

            // 2.2. 使用已有的 interview-analysis-v2.st 模板
            BeanOutputConverter<InterviewEvaluation> converter = new BeanOutputConverter<>(InterviewEvaluation.class);
//            org.springframework.core.io.Resource promptResource = resourceLoader.getResource("classpath:prompts/interview-analysis-v2.st");
//            PromptTemplate promptTemplate = new PromptTemplate(promptResource);

            Map<String, Object> variables = Map.of(
                    "jd_context", jdContext,
                    "resume_context", resumeContext,
                    "conversation", perQuestionAnalysisReport.toString(), // 关键：输入是“逐题分析报告”
                    "format", converter.getFormat()
            );

            Prompt finalSummaryPrompt = finalSummaryTemplate.create(variables);

            // 2.3. 调用LLM获取最终的JSON评估报告
            String rawJsonFromAI = qwenModel.call(finalSummaryPrompt).getResult().getOutput().getText();
            //计算耗时
            Instant fullEndTime = Instant.now();
            Duration duration = Duration.between(fullStartTime, fullEndTime);
            log.info("【RAG1】生成最终总结完成，耗时: {} s {} ms", duration.toSeconds() , duration.toMillis());
            //转化为Pojo
            InterviewEvaluation evaluation;
            try {
                evaluation = converter.convert(rawJsonFromAI);
                log.info("【RAG 目标1】AI输出结构化成功。");
            } catch (Exception e) {
                log.error("【RAG 目标1】AI输出结构化失败！AI原始返回: {}", rawJsonFromAI, e);
                throw new RuntimeException("AI 输出解析失败，请检查 Prompt 或 AI 模型状态", e);
            }

            // --- 3. 保存结果 ---
            // 注意：我们将 "逐题分析报告" 存入 answerContent，将 "最终JSON" 存入 result
            saveAnalysisResult(submitAnswerBO, perQuestionAnalysisReport.toString(), evaluation);

        // --- 4. 【可选】触发 RAG 目标 2 (课程推荐) ---
        // 你可以在这里（或在前端）调用
        // List<String> recommendations = recommendCourses(evaluation);
        // log.info("【RAG 目标2】推荐课程: {}", recommendations);
    }

//

    /**
     * 【已重构】保存方法，接收强类型的Evaluation对象
     */
    private void saveAnalysisResult(SubmitAnswerBO bo, String perQuestionAnalysis, InterviewEvaluation evaluation) {
        try {
            // 将强类型的POJO转为JSON字符串存入数据库
            String cleanedJson = JsonUtils.objectToJson(evaluation); //

            Job job = jobService.getDetail(bo.getJobId()); //
            InterviewRecord record = new InterviewRecord(); //
            record.setCandidateId(bo.getCandidateId());
            // answerContent 存储逐题分析详情，result 存储最终的JSON总结
            record.setJobName(job.getJobName());
            record.setAnswerContent(perQuestionAnalysis);
            record.setResult(cleanedJson);
            record.setTakeTime(bo.getTotalSeconds());
            record.setCreateTime(LocalDateTime.now());
            record.setUpdatedTime(LocalDateTime.now());

            interviewRecordService.save(record); //
            log.info("【RAG 目标1】AI分析结果已成功保存到数据库，候选人ID: {}", bo.getCandidateId());

        } catch (Exception e) {
            log.error("【RAG 目标1】保存AI评估结果失败", e);
        }
    }

    /**
     //     * RAG 目标1: 用于“单题评估”的Prompt
     //     */
//    private String buildPerQuestionPrompt(String question, String candidateAnswer, String criteria) {
//        return String.format(
//                "### 角色\n" +
//                        "你是一名严格的Java技术面试官，请对候选人的单个回答进行打分和评价。\n\n" +
//                        "### 评估标准 (参考答案与评分点)\n" +
//                        "```\n%s\n```\n\n" +
//                        "### 面试内容\n" +
//                        "**问题**：%s\n" +
//                        "**候选人回答**：%s\n\n" +
//                        "### 你的任务\n" +
//                        "1.  **对比**：请严格按照【评估标准】来对比【候选人回答】。\n" +
//                        "2.  **评价**：请给出针对这个回答的具体反馈，指出其优点和遗漏的知识点（例如：'你的回答提到了 synchronized，但未提及 ReentrantLock 和 CAS，建议补充...'）。\n" +
//                        "3.  **格式**：请直接输出评价文字，不要使用JSON。\n\n" +
//                        "### 评价：",
//                criteria,
//                question,
//                candidateAnswer
//        );
//    }


//    // =================================================================================
//    // RAG 目标 2: 推荐相关课程
//    // =================================================================================
//
//    /**
//     * RAG 目标2: 根据评估结果中的“薄弱点”推荐课程
//     * @param evaluation 评估报告
//     * @return 推荐话术列表
//     */
//    public List<String> recommendCourses(InterviewEvaluation evaluation) {
//        log.info("【RAG 目标2: 课程推荐】开始... ");
//        List<String> weaknesses = evaluation.getOverallEvaluation().getWeaknesses(); //
//        if (weaknesses == null || weaknesses.isEmpty()) {
//            log.info("【RAG 目标2】候选人无明显薄弱点，不推荐课程。");
//            return List.of();
//        }
//
//        // 将所有薄弱点合并为一个查询
//        String weaknessesQuery = String.join(", ", weaknesses);
//
//        // RAG检索：用“薄弱点”去知识库检索“课程”
//        SearchRequest searchRequest = SearchRequest.builder()
//                .query(weaknessesQuery)
//                .topK(2) // 最多推荐2门课
//                .filterExpression("doc_type == 'course'") // 关键：指定知识库
//                .build();
//
//        List<Document> courseDocs = vectorStore.similaritySearch(searchRequest);
//        if (courseDocs.isEmpty()) {
//            log.info("【RAG 目标2】未找到匹配的课程。");
//            return List.of();
//        }
//
//        // 准备上下文
//        String retrievedCourses = courseDocs.stream()
//                .map(doc -> "课程名称: " + doc.getId() + "\n课程介绍: " + doc.getText())
//                .collect(Collectors.joining("\n\n"));
//
//        // 构建Prompt，让AI生成自然的推荐语
//        String recommendationPrompt = String.format(
//                "### 角色\n" +
//                        "你是一位友善的面试助手。\n\n" +
//                        "### 候选人薄弱点\n" +
//                        "%s\n\n" +
//                        "### 可推荐的课程\n" +
//                        "```\n%s\n```\n\n" +
//                        "### 任务\n" +
//                        "请根据候选人的薄弱点，从课程列表中选择匹配的课程，并生成1-2句自然的推荐话术。" +
//                        "例如：'注意到你在索引优化方面有些犹豫，我们有一门实战课专门讲解这个...'",
//                weaknessesQuery,
//                retrievedCourses
//        );
//
//        String recommendation = chatModel.call(new Prompt(recommendationPrompt)).getResult().getOutput().getText();
//
//        log.info("【RAG 目标2】生成推荐语: {}", recommendation);
//        return List.of(recommendation);
//    }
//
//
//    // =================================================================================
//    // RAG 目标 3: 防止模型编造（事实问答）
//    // =================================================================================
//
//    /**
//     * RAG 目标3: 基于公司文档回答问题，防止AI幻觉
//     * @param question 用户的提问 (例如: "面试流程是怎样的？")
//     * @return 基于官方文档的回答
//     */
//    public String getCompanyPolicy(String question) {
//        log.info("【RAG 目标3: 事实问答】收到提问: {}", question);
//
//        // RAG检索：用“问题”去知识库检索“公司文档”
//        SearchRequest searchRequest = SearchRequest.builder()
//                .query(question)
//                .topK(1)
//                .filterExpression("doc_type == 'company_policy'") // 关键：指定知识库
//                .build();
//
//        List<Document> policyDocs = vectorStore.similaritySearch(searchRequest);
//
//        String retrievedPolicy = policyDocs.isEmpty()
//                ? "未检索到相关信息"
//                : policyDocs.get(0).getText();
//
//        // 关键：使用严格的Prompt模板来"约束"LLM
//        String factCheckPrompt = String.format(
//                "### 角色\n" +
//                        "你是公司HR助手，你的回答必须严格基于【官方文档】。\n\n" +
//                        "### 用户提问\n" +
//                        "%s\n\n" +
//                        "### 【官方文档】\n" +
//                        "```\n%s\n```\n\n" +
//                        "### 你的任务\n" +
//                        "1.  **检查文档**：请在【官方文档】中查找用户提问的答案。\n" +
//                        "2.  **生成回答**：\n" +
//                        "    - **如果**在文档中找到了明确答案，请直接使用文档内容回答。\n" +
//                        "    - **如果**文档内容为 '未检索到相关信息' 或与提问无关，你**必须**回答：'该问题暂无官方参考答案，建议您咨询HR获取帮助。'\n" +
//                        "3.  **禁止**：严禁编造【官方文档】中没有的信息。\n\n" +
//                        "### 回答：",
//                question,
//                retrievedPolicy
//        );
//
//        String answer = chatModel.call(new Prompt(factCheckPrompt)).getResult().getOutput().getText();
//        log.info("【RAG 目标3】生成回答: {}", answer);
//        return answer;
//    }
}