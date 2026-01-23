package com.axle.service.RAG;

import com.axle.bo.AnswerBO;
import com.axle.bo.SubmitAnswerBO;
import com.axle.pojo.Candidate;
import com.axle.pojo.InterviewRecord;
import com.axle.pojo.Job;
import com.axle.service.CandidateService;
import com.axle.service.InterviewRecordService;
import com.axle.service.JobService;
import com.axle.service.RAG.TextProcessingService;
import com.axle.utils.JsonUtils;
import com.axle.vo.ai.InterviewEvaluation;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 智能评估服务 (RAG)
 * 负责面试后的问答分析与总结，支持多种 RAG 目标
 */
@Service
@Slf4j
public class ChatGLMService {

    private final ChatModel qwenModel;
    private final VectorStore vectorStore;

    @Resource
    private JobService jobService;
    @Resource
    private InterviewRecordService interviewRecordService;
    @Resource
    private CandidateService candidateService;
    @Resource
    private com.axle.service.ResumeService resumeService;
    @Autowired
    private ResourceLoader resourceLoader;
    @Resource
    private AiModelWrapperService aiModelWrapper;
    @Resource
    private TextProcessingService textProcessingService;

    private PromptTemplate finalSummaryTemplate; // 简历模式模板
    private PromptTemplate questionLibFinalSummaryTemplate; // 题库模式模板
    private PromptTemplate perQuestionTemplate;
    private PromptTemplate courseRecommendationTemplate;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatGLMService(@Qualifier("zhipuAiChatModel") ChatModel qwenModel,
                          VectorStore vectorStore) {
        this.qwenModel = qwenModel;
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void initTemplates(){
        log.info("【ChatGLMService】正在预加载 Prompt 模板...");
        org.springframework.core.io.Resource singleRes = resourceLoader.getResource("classpath:newprompts/singleanalysis.st");
        perQuestionTemplate = new PromptTemplate(singleRes);
        // 简历模式模板
        org.springframework.core.io.Resource finalRes = resourceLoader.getResource("classpath:newprompts/finalanalysis.st");
        finalSummaryTemplate = new PromptTemplate(finalRes);
        // 题库模式模板
        org.springframework.core.io.Resource questionLibRes = resourceLoader.getResource("classpath:newprompts/questionlib-finalanalysis.st");
        questionLibFinalSummaryTemplate = new PromptTemplate(questionLibRes);
        org.springframework.core.io.Resource courseRes = resourceLoader.getResource("classpath:newprompts/course-recommendation.st");
        courseRecommendationTemplate = new PromptTemplate(courseRes);
    }

    // =================================================================================
    // RAG 目标 1: 回答评估与反馈生成 (核心业务)
    // =================================================================================
    public void analyze(SubmitAnswerBO submitAnswerBO) throws Exception {
        log.info("【RAG 目标1】开始分析候选人: {}", submitAnswerBO.getCandidateId());
        Instant fullStartTime = Instant.now();

        // 统计题目信息
        List<AnswerBO> allQuestions = submitAnswerBO.getQuestionAnswerList();
        List<AnswerBO> answeredQuestions = allQuestions.stream()
                .filter(answer -> answer.getAnswerContent() != null && !answer.getAnswerContent().trim().isEmpty())
                .toList();
        List<AnswerBO> unansweredQuestions = allQuestions.stream()
                .filter(answer -> answer.getAnswerContent() == null || answer.getAnswerContent().trim().isEmpty())
                .toList();
        
        log.info("【RAG 目标1】题目统计 - 总题目数: {}, 已作答: {}, 未作答: {}", 
                allQuestions.size(), answeredQuestions.size(), unansweredQuestions.size());
        
        if (!unansweredQuestions.isEmpty()) {
            log.warn("【RAG 目标1】以下题目未作答，将标记为未作答: {}", 
                    unansweredQuestions.stream().map(AnswerBO::getQuestion).collect(Collectors.joining("; ")));
        }

        // 1. 逐题评估 (并行执行) - 评估所有题目，包括未作答的
        List<CompletableFuture<String>> analysisFutures = allQuestions.stream()
                .map(answer -> CompletableFuture.supplyAsync(() -> {
                            try {
                                String questionText = answer.getQuestion();
                                String answerContent = answer.getAnswerContent();

                                // 如果答案为空，直接返回未作答标记
                                if (answerContent == null || answerContent.trim().isEmpty()) {
                                    log.warn("【RAG 目标1】题目未作答: {}", questionText);
                                    return "问题：" + questionText + "\n" + 
                                           "候选人未作答此题。\n\n";
                                }
                                
                        // RAG检索评分标准（优化策略：提高topK，优先检索评分标准，如果没找到则从课程资料中检索相关技术点）
                        String criteria = retrieveScoringCriteria(questionText);

                                Map<String, Object> variables = Map.of("question", questionText,
                                "candidateAnswer", answerContent,
                                "criteria", criteria);

                        String analysisResult = aiModelWrapper.callAiModel(qwenModel, perQuestionTemplate.create(variables), "单题分析");
                        // 清理Markdown符号
                        analysisResult = cleanMarkdownSymbols(analysisResult);
                        return "问题：" + questionText + "\n" + analysisResult + "\n\n";
                            } catch (Exception e) {
                        log.error("【RAG 目标1】单题分析异常: {}", answer.getQuestion(), e);
                        return "问题：" + answer.getQuestion() + "\nAI评估异常。\n\n";
                            }
                }, executor))
                .toList();

        CompletableFuture.allOf(analysisFutures.toArray(new CompletableFuture[0])).join();
        String perQuestionAnalysisReport = analysisFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.joining());

        log.info("【RAG 目标1】逐题评估完成。准备生成最终总结报告...");

        // 2. 生成最终总结报告（根据面试模式选择不同模板）
        Integer interviewMode = submitAnswerBO.getInterviewMode();
        if (interviewMode == null) {
            interviewMode = 1; // 默认简历模式
        }
        
        Job job = jobService.getDetail(submitAnswerBO.getJobId());
        Candidate candidate = candidateService.getDetail(submitAnswerBO.getCandidateId());
        String jdContext = (job != null && job.getJobDesc() != null) ? job.getJobDesc() : "未提供职位描述。";
        String resumeContext = resumeService.getResume(submitAnswerBO.getCandidateId());
        if (resumeContext == null && candidate != null && candidate.getRemark() != null) {
            resumeContext = candidate.getRemark();
        }
        if (resumeContext == null) resumeContext = "未提供简历信息。";

        BeanOutputConverter<InterviewEvaluation> converter = new BeanOutputConverter<>(InterviewEvaluation.class);
        PromptTemplate selectedTemplate;
        Map<String, Object> variables;

        // 根据模式选择模板和构建变量
        if (interviewMode == 3) {
            // 题库抽取模式：使用题库模式模板
            selectedTemplate = questionLibFinalSummaryTemplate;
            variables = Map.of(
                "conversation", perQuestionAnalysisReport,
                "format", converter.getFormat()
            );
            log.info("【RAG 目标1】使用题库模式模板");
        } else {
            // 简历模式（默认）：使用简历模式模板
            selectedTemplate = finalSummaryTemplate;
            variables = Map.of(
                "jd_context", textProcessingService.truncateText(jdContext, 3000),
                "resume_context", textProcessingService.truncateText(resumeContext, 3000),
                "conversation", perQuestionAnalysisReport,
                "format", converter.getFormat()
            );
            log.info("【RAG 目标1】使用简历模式模板");
        }

        String rawJsonFromAI = aiModelWrapper.callAiModel(qwenModel, selectedTemplate.create(variables), "最终总结报告");
        log.info("【RAG 目标1】AI返回的原始JSON: {}", rawJsonFromAI);
        
        InterviewEvaluation evaluation = converter.convert(rawJsonFromAI);
        if (evaluation == null) {
            log.error("【RAG 目标1】JSON转换失败，evaluation为null。原始JSON: {}", rawJsonFromAI);
            throw new RuntimeException("AI返回的评估结果转换失败");
            }
        log.info("【RAG 目标1】评估结果转换成功，scores: {}, overall_evaluation: {}", 
                evaluation.getScores() != null ? "存在" : "null",
                evaluation.getOverallEvaluation() != null ? "存在" : "null");

        // 3. 保存结果
        String jobName = (job != null && job.getJobName() != null) ? job.getJobName() : "未知职位";
        saveAnalysisResult(submitAnswerBO, textProcessingService.truncateText(perQuestionAnalysisReport, 10000), evaluation, jobName);

        log.info("【RAG 目标1】分析完成，总耗时: {}ms", Duration.between(fullStartTime, Instant.now()).toMillis());
    }

    // =================================================================================
    // RAG 检索方法：智能检索评分标准（支持动态提问）
    // =================================================================================
    /**
     * 检索评分标准，支持动态提问
     * 策略：1. 优先从评分标准库检索（topK=3，选择最相关的）
     *      2. 如果没找到，从课程资料中检索相关技术点作为参考标准
     */
    private String retrieveScoringCriteria(String questionText) {
        try {
            // 策略1：优先检索评分标准（提高topK到3，选择最相关的）
            SearchRequest scoringRequest = SearchRequest.builder()
                    .query(questionText)
                    .topK(3)
                    .filterExpression("doc_type == 'scoring_criteria'")
                    .build();
            List<Document> scoringDocs = vectorStore.similaritySearch(scoringRequest);
            
            if (scoringDocs != null && !scoringDocs.isEmpty()) {
                log.debug("【RAG检索】找到 {} 条评分标准，选择最相关的一条", scoringDocs.size());
                return scoringDocs.get(0).getText();  // 最相关的就是第一个
            }
            
            // 策略2：如果没找到评分标准，从课程资料中检索相关技术点作为参考
            log.debug("【RAG检索】未找到评分标准，尝试从课程资料检索相关技术点");
            SearchRequest courseRequest = SearchRequest.builder()
                    .query(questionText)
                    .topK(2)
                    .filterExpression("doc_type == 'course'")
                    .build();
            List<Document> courseDocs = vectorStore.similaritySearch(courseRequest);
            
            if (courseDocs != null && !courseDocs.isEmpty()) {
                StringBuilder fallbackCriteria = new StringBuilder("【参考标准】（未找到官方评分标准，基于相关技术资料）\n");
                for (Document doc : courseDocs) {
                    fallbackCriteria.append(doc.getText()).append("\n\n");
                }
                return fallbackCriteria.toString();
    }

            // 策略3：完全找不到，使用通用提示
            log.warn("【RAG检索】未找到任何相关评分标准或技术资料，问题：{}", questionText);
            return "暂无官方评分标准，请基于通用知识进行评估。";
            } catch (Exception e) {
            log.error("【RAG检索】检索评分标准异常", e);
            return "暂无官方评分标准，请基于通用知识进行评估。";
        }
    }



    private void saveAnalysisResult(SubmitAnswerBO bo, String perQuestionAnalysis, InterviewEvaluation evaluation, String jobName) {
        try {
            if (evaluation == null) {
                log.error("【RAG 目标1】保存评估结果失败：evaluation为null");
                return;
            }
            
            String resultJson = JsonUtils.objectToJson(evaluation);
            if (resultJson == null || resultJson.isEmpty()) {
                log.error("【RAG 目标1】保存评估结果失败：JSON序列化返回null或空字符串，evaluation: {}", evaluation);
                return;
            }
            
            log.info("【RAG 目标1】准备保存评估结果，候选人ID: {}, resultJson长度: {}", bo.getCandidateId(), resultJson.length());
            
            InterviewRecord record = new InterviewRecord();
            record.setCandidateId(bo.getCandidateId());
            record.setJobName(jobName);
            // 再次清理Markdown符号（确保完全清理）
            String cleanedAnalysis = cleanMarkdownSymbols(perQuestionAnalysis);
            // 将逐题分析结果包装为 HTML，便于前端直接以 HTML 形式展示
            // 这里使用 <pre> 标签保留原有换行和排版
            String perQuestionAnalysisHtml = "<pre style=\"white-space: pre-wrap;\">" + cleanedAnalysis + "</pre>";
            record.setAnswerContent(perQuestionAnalysisHtml);
            record.setResult(resultJson);
            record.setTakeTime(bo.getTotalSeconds());
            record.setCreateTime(LocalDateTime.now());
            record.setUpdatedTime(LocalDateTime.now());
            interviewRecordService.save(record);
            String recordId = record.getId();
            log.info("【RAG 目标1】评估结果保存成功，候选人ID: {}, 记录ID: {}", bo.getCandidateId(), recordId);
            
            // 异步生成推荐课程（如果ID存在）
            if (recordId != null && !recordId.isEmpty()) {
                log.info("【推荐课程】准备异步生成推荐课程，记录ID: {}, 候选人ID: {}", recordId, bo.getCandidateId());
                // 提取题目列表，用于评分低时根据题目检索课程
                List<String> questionList = bo.getQuestionAnswerList() != null 
                        ? bo.getQuestionAnswerList().stream()
                                .map(answer -> answer.getQuestion())
                                .filter(q -> q != null && !q.trim().isEmpty())
                                .toList()
                        : new java.util.ArrayList<>();
                CompletableFuture.runAsync(() -> {
                    try {
                        log.info("【推荐课程】异步任务开始执行，记录ID: {}", recordId);
                        generateAndSaveRecommendedCourses(recordId, evaluation, perQuestionAnalysis, questionList);
                        log.info("【推荐课程】异步任务执行完成，记录ID: {}", recordId);
                    } catch (Exception e) {
                        log.error("【推荐课程】异步生成推荐课程失败，记录ID: {}", recordId, e);
                        log.error("【推荐课程】异常堆栈", e);
                    }
                }, executor);
            } else {
                log.warn("【推荐课程】记录ID为空，跳过推荐课程生成，候选人ID: {}", bo.getCandidateId());
            }
        } catch (Exception e) {
            log.error("【RAG 目标1】保存评估结果失败", e);
            throw e; // 重新抛出异常，让调用者知道保存失败
        }
    }

    /**
     * 异步生成并保存推荐课程
     */
    private void generateAndSaveRecommendedCourses(String recordId, InterviewEvaluation evaluation, 
                                                     String perQuestionAnalysis, List<String> questionList) {
        try {
            log.info("【推荐课程】开始生成推荐课程，记录ID: {}", recordId);
            
            // 1. 计算综合得分，判断评分是否低
            int avgScore = calculateAverageScore(evaluation);
            boolean isLowScore = avgScore < 60; // 低于60分视为低分
            log.info("【推荐课程】综合得分: {}, 是否低分: {}", avgScore, isLowScore);
            
            // 2. 从评估结果中提取薄弱点
            String weaknesses = "";
            if (evaluation != null && evaluation.getOverallEvaluation() != null 
                    && evaluation.getOverallEvaluation().getWeaknesses() != null) {
                weaknesses = String.join("；", evaluation.getOverallEvaluation().getWeaknesses());
                log.info("【推荐课程】提取到薄弱点: {}", weaknesses);
            } else {
                log.warn("【推荐课程】未找到薄弱点，evaluation: {}, overallEvaluation: {}", 
                        evaluation != null, 
                        evaluation != null && evaluation.getOverallEvaluation() != null);
            }
            
            // 3. 构建查询文本（优化：将"薄弱点"转换为"学习需求"，提升RAG检索准确性）
            // 核心问题：薄弱点说的是"什么弱"（问题描述），而课程文档说的是"学习内容"（解决方案）
            // 解决方案：提取技术关键词，构建符合课程文档语义的查询文本
            String queryText = buildLearningQueryText(weaknesses, perQuestionAnalysis, questionList, isLowScore);
            
            // 如果查询文本仍然为空，使用评估摘要作为查询文本
            if (queryText == null || queryText.isEmpty()) {
                String evaluationSummary = buildEvaluationSummary(evaluation);
                queryText = evaluationSummary != null && !evaluationSummary.isEmpty() 
                        ? evaluationSummary 
                        : "技术学习";
                log.warn("【推荐课程】查询文本为空，使用默认查询文本: {}", queryText);
            }
            
            log.info("【推荐课程】最终查询文本长度: {}, 预览: {}", 
                    queryText.length(), 
                    queryText.length() > 100 ? queryText.substring(0, 100) + "..." : queryText);
            
            // 4. RAG检索相关课程
            log.info("【推荐课程】开始RAG检索相关课程...");
            List<Document> courseDocs = retrieveCourseDocuments(queryText);
            log.info("【推荐课程】RAG检索完成，检索到 {} 条课程文档", courseDocs != null ? courseDocs.size() : 0);
            
            // 5. 构建评估摘要
            String evaluationSummary = buildEvaluationSummary(evaluation);
            log.info("【推荐课程】评估摘要长度: {}", evaluationSummary != null ? evaluationSummary.length() : 0);
            
            // 6. 调用AI生成推荐课程
            String recommendedCoursesJson = generateCourseRecommendation(
                    evaluationSummary, weaknesses, courseDocs);
            
            // 7. 更新数据库
            if (recommendedCoursesJson != null && !recommendedCoursesJson.isEmpty()) {
                log.info("【推荐课程】准备更新数据库，记录ID: {}, JSON长度: {}, JSON预览: {}", 
                        recordId, recommendedCoursesJson.length(), 
                        recommendedCoursesJson.length() > 100 ? recommendedCoursesJson.substring(0, 100) + "..." : recommendedCoursesJson);
                
                InterviewRecord record = new InterviewRecord();
                record.setId(recordId);
                record.setRecommendedCourses(recommendedCoursesJson);
                record.setUpdatedTime(LocalDateTime.now());
                
                try {
                    int updateResult = interviewRecordService.updateById(record);
                    log.info("【推荐课程】数据库更新操作完成，记录ID: {}, 更新结果: {}", recordId, updateResult > 0 ? "成功" : "可能失败");
                    
                    if (updateResult > 0) {
                        log.info("【推荐课程】推荐课程生成并保存成功，记录ID: {}, JSON长度: {}", 
                                recordId, recommendedCoursesJson.length());
                    } else {
                        log.warn("【推荐课程】数据库更新可能失败（影响行数为0），记录ID: {}", recordId);
                    }
                } catch (Exception e) {
                    log.error("【推荐课程】数据库更新异常，记录ID: {}", recordId, e);
                    log.error("【推荐课程】异常堆栈详情", e);
                    throw e; // 重新抛出异常，让调用者知道更新失败
                }
            } else {
                log.warn("【推荐课程】生成的推荐课程JSON为空，记录ID: {}", recordId);
            }
        } catch (Exception e) {
            log.error("【推荐课程】生成推荐课程异常，记录ID: {}", recordId, e);
            // 记录详细的异常堆栈信息，便于排查问题
            log.error("【推荐课程】异常详情", e);
        }
    }

    /**
     * RAG检索课程文档
     */
    private List<Document> retrieveCourseDocuments(String queryText) {
        try {
            // 策略1：使用原始查询文本检索
            SearchRequest courseRequest = SearchRequest.builder()
                    .query(queryText)
                    .topK(5)
                    .filterExpression("doc_type == 'course'")
                    .build();
            List<Document> courseDocs = vectorStore.similaritySearch(courseRequest);
            
            if (courseDocs != null && !courseDocs.isEmpty()) {
                log.info("【推荐课程】检索到 {} 条相关课程", courseDocs.size());
                return courseDocs;
            }
            
            log.warn("【推荐课程】未检索到相关课程，尝试提取关键词重新检索...");

            // 策略2：提取关键词重新检索（从薄弱点中提取技术关键词）
            String keywords = extractKeywords(queryText);
            if (keywords != null && !keywords.isEmpty() && !keywords.equals(queryText)) {
                log.info("【推荐课程】使用提取的关键词重新检索: {}", keywords);
                SearchRequest keywordRequest = SearchRequest.builder()
                        .query(keywords)
                        .topK(5)
                        .filterExpression("doc_type == 'course'")
                        .build();
                courseDocs = vectorStore.similaritySearch(keywordRequest);
                
                if (courseDocs != null && !courseDocs.isEmpty()) {
                    log.info("【推荐课程】使用关键词检索到 {} 条相关课程", courseDocs.size());
                    return courseDocs;
                }
            }
            
            // 策略3：尝试更宽泛的查询（移除过滤器）
            log.warn("【推荐课程】尝试更宽泛的查询（不限制文档类型）...");
            SearchRequest broadRequest = SearchRequest.builder()
                    .query(keywords != null && !keywords.isEmpty() ? keywords : queryText)
                    .topK(3)
                    .build();
            List<Document> broadDocs = vectorStore.similaritySearch(broadRequest);
            
            if (broadDocs != null && !broadDocs.isEmpty()) {
                // 过滤出可能是课程的内容
                List<Document> filteredDocs = broadDocs.stream()
                        .filter(doc -> {
                            String text = doc.getText().toLowerCase();
                            return text.contains("课程") || text.contains("学习") || 
                                   text.contains("教程") || text.contains("培训") ||
                                   text.contains("course") || text.contains("tutorial");
                        })
                        .limit(3)
                        .toList();
                
                if (!filteredDocs.isEmpty()) {
                    log.info("【推荐课程】宽泛查询找到 {} 条可能的课程文档", filteredDocs.size());
                    return filteredDocs;
                }
            }
            
            log.warn("【推荐课程】所有检索策略均未找到相关课程，将基于薄弱点生成通用推荐");
            return new java.util.ArrayList<>();
        } catch (Exception e) {
            log.error("【推荐课程】检索课程文档异常", e);
            return new java.util.ArrayList<>();
        }
    }
    
    /**
     * 构建学习需求查询文本（将"薄弱点"转换为"学习需求"）
     * 核心优化：将"什么弱"转换为"需要学习什么"，提升RAG检索准确性
     */
    private String buildLearningQueryText(String weaknesses, String perQuestionAnalysis, 
                                          List<String> questionList, boolean isLowScore) {
        // 策略1：从薄弱点中提取技术关键词，转换为学习需求
        String techKeywords = extractTechKeywordsFromWeaknesses(weaknesses);
        
        // 策略2：从题目中提取技术关键词
        String questionKeywords = extractTechKeywordsFromQuestions(questionList);
        
        // 策略3：从逐题分析中提取技术关键词
        String analysisKeywords = extractTechKeywordsFromAnalysis(perQuestionAnalysis);
        
        // 合并所有关键词，构建查询文本
        StringBuilder queryBuilder = new StringBuilder();
        
        // 优先使用薄弱点转换后的技术关键词
        if (techKeywords != null && !techKeywords.isEmpty()) {
            queryBuilder.append(techKeywords);
        }
        
        // 补充题目中的技术关键词
        if (questionKeywords != null && !questionKeywords.isEmpty()) {
            if (queryBuilder.length() > 0) {
                queryBuilder.append(" ");
            }
            queryBuilder.append(questionKeywords);
        }
        
        // 补充逐题分析中的技术关键词
        if (analysisKeywords != null && !analysisKeywords.isEmpty()) {
            if (queryBuilder.length() > 0) {
                queryBuilder.append(" ");
            }
            queryBuilder.append(analysisKeywords);
        }
        
        String finalQuery = queryBuilder.toString().trim();
        
        // 如果关键词提取失败，降级使用原始文本
        if (finalQuery.isEmpty()) {
            if (isLowScore && questionList != null && !questionList.isEmpty()) {
                // 评分低时，使用题目（前3个）
                List<String> limitedQuestions = questionList.size() > 3 
                        ? questionList.subList(0, 3) 
                        : questionList;
                finalQuery = String.join("；", limitedQuestions);
                log.info("【推荐课程】关键词提取失败，使用题目作为查询文本，题目数量: {}", limitedQuestions.size());
            } else if (weaknesses != null && !weaknesses.isEmpty()) {
                // 使用薄弱点
                finalQuery = weaknesses;
                log.info("【推荐课程】关键词提取失败，使用薄弱点作为查询文本，长度: {}", finalQuery.length());
            } else if (perQuestionAnalysis != null && !perQuestionAnalysis.isEmpty()) {
                // 使用逐题分析的前500字
                String cleanAnalysis = perQuestionAnalysis.replaceAll("<[^>]+>", "").trim();
                finalQuery = cleanAnalysis.length() > 500 ? cleanAnalysis.substring(0, 500) : cleanAnalysis;
                log.info("【推荐课程】关键词提取失败，使用逐题分析作为查询文本，长度: {}", finalQuery.length());
            }
        } else {
            log.info("【推荐课程】成功提取技术关键词构建查询文本: {}", finalQuery);
        }
        
        return finalQuery;
    }
    
    /**
     * 从薄弱点中提取技术关键词（将"什么弱"转换为"需要学习什么"）
     * 例如："算法基础薄弱" -> "算法 数据结构"
     *      "对多线程理解不深" -> "多线程 并发编程"
     */
    private String extractTechKeywordsFromWeaknesses(String weaknesses) {
        if (weaknesses == null || weaknesses.isEmpty()) {
            return null;
        }
        
        // 薄弱点到技术关键词的映射规则
        Map<String, String> weaknessToTechMap = Map.ofEntries(
            Map.entry("算法", "算法 数据结构 排序 动态规划"),
            Map.entry("数据结构", "数据结构 数组 链表 树 图"),
            Map.entry("多线程", "多线程 并发编程 线程安全 锁"),
            Map.entry("并发", "并发编程 多线程 线程池 同步"),
            Map.entry("JVM", "JVM 垃圾回收 内存模型 类加载"),
            Map.entry("Spring", "Spring IoC AOP Bean 事务"),
            Map.entry("Spring Boot", "Spring Boot 自动配置 Starter"),
            Map.entry("微服务", "微服务 Spring Cloud 分布式"),
            Map.entry("MySQL", "MySQL 索引 SQL优化 事务"),
            Map.entry("Redis", "Redis 缓存 持久化 集群"),
            Map.entry("消息队列", "消息队列 RabbitMQ Kafka MQ"),
            Map.entry("分布式", "分布式 CAP 分布式事务 分布式锁"),
            Map.entry("设计模式", "设计模式 工厂 单例 代理"),
            Map.entry("网络", "HTTP HTTPS TCP 网络协议")
        );
        
        StringBuilder keywords = new StringBuilder();
        String lowerWeaknesses = weaknesses.toLowerCase();
        
        // 匹配薄弱点关键词
        for (Map.Entry<String, String> entry : weaknessToTechMap.entrySet()) {
            if (lowerWeaknesses.contains(entry.getKey().toLowerCase())) {
                if (keywords.length() > 0) {
                    keywords.append(" ");
                }
                keywords.append(entry.getValue());
            }
        }
        
        // 如果没有匹配到，尝试直接提取技术关键词
        if (keywords.length() == 0) {
            keywords.append(extractKeywords(weaknesses));
        }
        
        return keywords.length() > 0 ? keywords.toString() : null;
    }
    
    /**
     * 从题目列表中提取技术关键词
     */
    private String extractTechKeywordsFromQuestions(List<String> questionList) {
        if (questionList == null || questionList.isEmpty()) {
            return null;
        }
        
        // 合并所有题目文本
        String allQuestions = String.join(" ", questionList);
        return extractKeywords(allQuestions);
    }
    
    /**
     * 从逐题分析中提取技术关键词
     */
    private String extractTechKeywordsFromAnalysis(String perQuestionAnalysis) {
        if (perQuestionAnalysis == null || perQuestionAnalysis.isEmpty()) {
            return null;
        }
        
        // 清理HTML标签
        String cleanAnalysis = perQuestionAnalysis.replaceAll("<[^>]+>", "").trim();
        if (cleanAnalysis.isEmpty()) {
            return null;
        }
        
        // 只提取前500字进行分析（避免过长）
        String analysisExcerpt = cleanAnalysis.length() > 500 ? cleanAnalysis.substring(0, 500) : cleanAnalysis;
        return extractKeywords(analysisExcerpt);
    }
    
    /**
     * 从文本中提取关键词（简化版：提取技术相关词汇）
     */
    private String extractKeywords(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // 常见技术关键词（扩展版）
        String[] techKeywords = {
            "Java", "Spring", "MySQL", "Redis", "微服务", "分布式", 
            "算法", "数据结构", "设计模式", "多线程", "JVM",
            "Spring Boot", "MyBatis", "Docker", "Kafka", "RabbitMQ",
            "并发", "线程", "锁", "volatile", "synchronized",
            "IoC", "AOP", "Bean", "事务", "索引", "SQL",
            "HTTP", "HTTPS", "TCP", "网络", "协议",
            "缓存", "持久化", "集群", "分库分表", "CAP",
            "工厂", "单例", "代理", "策略", "观察者"
        };
        
        StringBuilder keywords = new StringBuilder();
        String lowerText = text.toLowerCase();
        
        for (String keyword : techKeywords) {
            if (lowerText.contains(keyword.toLowerCase())) {
                if (keywords.length() > 0) {
                    keywords.append(" ");
                }
                keywords.append(keyword);
            }
        }
        
        // 如果提取到关键词，返回；否则返回null（让调用者使用降级策略）
        return keywords.length() > 0 ? keywords.toString() : null;
    }

    /**
     * 计算综合得分
     */
    private int calculateAverageScore(InterviewEvaluation evaluation) {
        if (evaluation == null || evaluation.getScores() == null) {
            return 0;
        }
        
        int technical = evaluation.getScores().getTechnicalAbility();
        int project = evaluation.getScores().getProjectExperience();
        int logical = evaluation.getScores().getLogicalThinking();
        
        return Math.round((technical + project + logical) / 3.0f);
    }
    
    /**
     * 构建评估摘要
     */
    private String buildEvaluationSummary(InterviewEvaluation evaluation) {
        if (evaluation == null) {
            return "评估结果为空";
        }
        
        StringBuilder summary = new StringBuilder();
        
        if (evaluation.getScores() != null) {
            summary.append("技术能力得分: ").append(evaluation.getScores().getTechnicalAbility()).append("；");
            summary.append("项目能力得分: ").append(evaluation.getScores().getProjectExperience()).append("；");
            summary.append("逻辑思维得分: ").append(evaluation.getScores().getLogicalThinking()).append("。");
        }
        
        if (evaluation.getOverallEvaluation() != null) {
            if (evaluation.getOverallEvaluation().getSummary() != null) {
                summary.append("\n综合评语: ").append(evaluation.getOverallEvaluation().getSummary());
            }
        }
        
        return summary.toString();
    }

    /**
     * 清理Markdown符号（** 和 ---）
     */
    private String cleanMarkdownSymbols(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // 移除 ** 符号（粗体标记）
        text = text.replace("**", "");
        
        // 移除 --- 符号（分隔线）
        text = text.replace("---", "");
        
        // 移除多余的换行和空白
        text = text.replaceAll("\n{3,}", "\n\n"); // 将3个以上连续换行替换为2个
        
        return text.trim();
    }

    /**
     * 清理AI返回的JSON响应，提取纯JSON部分
     * 处理markdown代码块格式：```json {...} ``` 或 ``` {...} ```
     */
    private String cleanJsonResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "{\"recommendations\":[],\"summary\":\"AI返回为空\"}";
        }
        
        String cleaned = rawResponse.trim();
        
        // 移除markdown代码块标记
        if (cleaned.startsWith("```")) {
            // 找到第一个换行符
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            } else {
                // 如果没有换行符，尝试找第一个{
                int firstBrace = cleaned.indexOf('{');
                if (firstBrace > 0) {
                    cleaned = cleaned.substring(firstBrace);
                }
            }
            
            // 移除结尾的```
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            // 移除结尾的```json
            if (cleaned.endsWith("```json")) {
                cleaned = cleaned.substring(0, cleaned.length() - 7);
            }
        }
        
        // 移除首尾空白字符
        cleaned = cleaned.trim();
        
        // 确保以{开头，}结尾
        int startBrace = cleaned.indexOf('{');
        int endBrace = cleaned.lastIndexOf('}');
        
        if (startBrace >= 0 && endBrace > startBrace) {
            cleaned = cleaned.substring(startBrace, endBrace + 1);
        } else if (!cleaned.startsWith("{")) {
            // 如果找不到JSON，返回默认值
            log.warn("【推荐课程】无法从AI响应中提取JSON，原始响应: {}", rawResponse);
            return "{\"recommendations\":[],\"summary\":\"JSON解析失败\"}";
        }
        
        return cleaned;
    }

    /**
     * 调用AI生成推荐课程
     */
    private String generateCourseRecommendation(String evaluationSummary, String weaknesses, List<Document> courseDocs) {
        try {
            // 构建检索到的课程文本
            StringBuilder coursesText = new StringBuilder();
            if (courseDocs != null && !courseDocs.isEmpty()) {
                coursesText.append("已从知识库检索到以下相关课程资料：\n\n");
                for (int i = 0; i < courseDocs.size(); i++) {
                    Document doc = courseDocs.get(i);
                    coursesText.append(i + 1).append(". ").append(doc.getId())
                            .append("：").append(doc.getText()).append("\n\n");
                }
            } else {
                coursesText.append("未从知识库检索到相关课程资料。");
                coursesText.append("请基于候选人的薄弱点，推荐通用的学习资源，包括但不限于：");
                coursesText.append("在线课程平台（如慕课网、极客时间、B站等）的课程，");
                coursesText.append("技术书籍，实践项目，官方文档，技术博客等。");
                coursesText.append("推荐内容必须与候选人的薄弱点直接相关。");
            }
            
            // 构建prompt变量
            Map<String, Object> variables = Map.of(
                    "evaluation_summary", evaluationSummary,
                    "weaknesses", weaknesses.isEmpty() ? "无明显薄弱点" : weaknesses,
                    "retrieved_courses", coursesText.toString()
            );
            
            // 调用AI生成推荐
            String recommendationJson = aiModelWrapper.callAiModel(
                    qwenModel, 
                    courseRecommendationTemplate.create(variables), 
                    "推荐课程生成");
            
            log.info("【推荐课程】AI返回的原始文本: {}", recommendationJson);
            
            // 清理AI返回的文本，提取纯JSON部分
            // AI可能返回markdown代码块格式，如 ```json {...} ``` 或 ``` {...} ```
            String cleanedJson = cleanJsonResponse(recommendationJson);
            
            log.info("【推荐课程】清理后的JSON: {}", cleanedJson);
            return cleanedJson;
        } catch (Exception e) {
            log.error("【推荐课程】生成推荐课程异常", e);
            return "{\"recommendations\":[],\"summary\":\"推荐课程生成失败\"}";
        }
    }
}
