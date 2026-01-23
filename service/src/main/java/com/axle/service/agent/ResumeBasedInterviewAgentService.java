package com.axle.service.agent;

import com.axle.service.agent.workflow.InterviewProcessService;
import com.axle.service.agent.workflow.InterviewWorkflowContext;
import com.axle.utils.JsonUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 上传简历面试Agent服务
 * 基于Flowable工作流引擎进行Agent自动化面试
 */
@Service
@Slf4j
public class ResumeBasedInterviewAgentService {

    @Autowired
    @Qualifier("zhipuAiChatModel")
    private ChatModel chatModel;

    @Autowired
    private InterviewProcessService interviewProcessService;

    @Autowired
    private InterviewWorkflowContext workflowContext;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ResourceLoader resourceLoader;

    private PromptTemplate resumeBasedInterviewTemplate;

    private static final String REDIS_AGENT_CONTEXT_PREFIX = "agent:resume:context:";
    private static final int CONTEXT_EXPIRE_HOURS = 24;
    /** 自我介绍阶段固定问题 */
    private static final String SELF_INTRO_QUESTION = "请先简单介绍一下你自己，包括教育背景、工作经历和主要技术方向。";
    
    // ========== 题数控制配置（仅保留硬性上限作为兜底保护）==========
    /** 最大题数：达到这个数量强制结束（硬性上限，防止无限提问） */
    private static final int MAX_QUESTION_COUNT = 10;
    
    /**
     * 强制结束面试
     * 
     * @param candidateId 候选人ID
     * @param emitter SSE发射器
     * @param message 结束消息
     */
    private void forceEndInterview(String candidateId, SseEmitter emitter, String message) {
        interviewProcessService.setVariable(candidateId, "shouldEnd", true);
        interviewProcessService.setVariable(candidateId, "shouldContinue", false);
        interviewProcessService.setVariable(candidateId, "finished", true);
        if (interviewProcessService.isWaitingForAnswer(candidateId)) {
            interviewProcessService.continueProcess(candidateId, "");
        }
        try {
            emitter.send(SseEmitter.event().name("question")
                .data("{\"content\":\"[面试结束] " + escapeJson(message) + "\",\"finished\":true,\"isEnd\":true}"));
            emitter.complete();
        } catch (IOException e) {
            log.error("【简历面试Agent】发送结束消息失败", e);
        }
    }

    @PostConstruct
    public void init() {
        log.info("【简历面试Agent】加载Prompt模板...");
        org.springframework.core.io.Resource resource = resourceLoader.getResource("classpath:newprompts/resumebased-interview-v2.st");
        this.resumeBasedInterviewTemplate = new PromptTemplate(resource);
    }

    /**
     * 初始化面试上下文（上传简历模式）
     * 启动Flowable工作流流程实例
     */
    public void initInterviewContext(String candidateId, String jobId) {
        log.info("【简历面试Agent】初始化上下文，候选人ID: {}, 职位ID: {}", candidateId, jobId);

        // 启动工作流流程实例
        String processInstanceId = interviewProcessService.startInterviewProcess(candidateId, jobId);
        log.info("【简历面试Agent】工作流流程实例已启动，流程实例ID: {}", processInstanceId);

        // 保存到Redis（用于兼容性，后续可以从流程变量中获取）
        ResumeBasedInterviewContext context = new ResumeBasedInterviewContext();
        context.candidateId = candidateId;
        context.jobId = jobId;
        context.questionCount = 0;
        context.coveredTechDimensions = new ArrayList<>();
        context.qaHistory = new ArrayList<>();

        String contextKey = REDIS_AGENT_CONTEXT_PREFIX + candidateId;
        String contextJson = JsonUtils.objectToJson(context);
        stringRedisTemplate.opsForValue().set(contextKey, contextJson, CONTEXT_EXPIRE_HOURS, TimeUnit.HOURS);

        log.info("【简历面试Agent】上下文初始化完成");
    }

    /**
     * 流式生成面试题（Agent模式）
     * 基于Flowable工作流引擎
     */
    public void generateQuestionStream(String candidateId, String lastAnswer, SseEmitter emitter) {
        log.info("【简历面试Agent】生成问题，候选人ID: {}, 上一题答案: {}", candidateId, 
                lastAnswer != null ? "有" : "无");

        // 检查流程是否已结束
        if (interviewProcessService.isProcessFinished(candidateId)) {
            try {
                emitter.send(SseEmitter.event().name("question")
                    .data("{\"content\":\"[面试结束] 感谢您的回答，面试已完成。\",\"finished\":true,\"isEnd\":true}"));
                emitter.complete();
            } catch (IOException e) {
                log.error("【简历面试Agent】发送结束消息失败", e);
            }
            return;
        }

        // 如果有上一题答案，先处理答案并继续流程
        if (lastAnswer != null && !lastAnswer.trim().isEmpty()) {
            processAnswer(candidateId, lastAnswer);
            // 继续流程执行
            interviewProcessService.continueProcess(candidateId, lastAnswer);
            // 等待流程执行完成（流程会执行到下一个服务任务或接收任务）
            // 需要等待足够的时间让流程执行完成
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(100); // 每次等待100ms，最多等待2秒
                    // 检查流程是否已结束
                    if (interviewProcessService.isProcessFinished(candidateId)) {
                        break;
                    }
                    // 检查流程是否在等待接收任务（说明流程已执行到等待状态）
                    if (interviewProcessService.isWaitingForAnswer(candidateId)) {
                        break;
                    }
                    // 检查流程实例是否还存在
                    ProcessInstance pi = interviewProcessService.getProcessInstance(candidateId);
                    if (pi == null) {
                        // 流程实例不存在，可能已结束
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 再次检查流程是否已结束
        if (interviewProcessService.isProcessFinished(candidateId)) {
            try {
                emitter.send(SseEmitter.event().name("question")
                    .data("{\"content\":\"[面试结束] 感谢您的回答，面试已完成。\",\"finished\":true,\"isEnd\":true}"));
                emitter.complete();
            } catch (IOException e) {
                log.error("【简历面试Agent】发送结束消息失败", e);
            }
            return;
        }

        // 获取流程实例
        ProcessInstance processInstance = interviewProcessService.getProcessInstance(candidateId);
        if (processInstance == null) {
            sendError(emitter, "流程实例不存在，请先初始化面试");
            return;
        }

        // 获取当前阶段（从流程变量中读取）
        String currentStage = (String) interviewProcessService.getVariable(candidateId, "currentStage");
        if (currentStage == null) {
            currentStage = "INIT";
        }

        // 检查是否已完成
        Boolean finished = (Boolean) interviewProcessService.getVariable(candidateId, "finished");
        if (Boolean.TRUE.equals(finished)) {
            try {
                emitter.send(SseEmitter.event().name("question")
                    .data("{\"content\":\"[面试结束] 感谢您的回答，面试已完成。\",\"finished\":true,\"isEnd\":true}"));
                emitter.complete();
            } catch (IOException e) {
                log.error("【简历面试Agent】发送结束消息失败", e);
            }
            return;
        }

        // 获取执行上下文（仅用于获取阶段信息，不用于设置变量）
        DelegateExecution execution = workflowContext.getCurrentExecution(candidateId);
        
        // 检查流程是否在等待答案（说明流程已执行到waitForAnswer）
        boolean isWaiting = interviewProcessService.isWaitingForAnswer(candidateId);
        if (!isWaiting && execution == null) {
            // 如果流程不在等待状态，且没有执行上下文，说明流程可能还在初始化
            // 等待一下让流程执行完成
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            execution = workflowContext.getCurrentExecution(candidateId);
        }
        
        // 根据当前阶段生成问题
        generateQuestionByStage(candidateId, execution, currentStage, emitter);
    }

    /**
     * 处理答案
     * 更新问答历史中最后一个问题的答案（问题已在生成时保存）
     */
    private void processAnswer(String candidateId, String lastAnswer) {
        // 获取问答历史
        @SuppressWarnings("unchecked")
        List<QAPair> qaHistory = (List<QAPair>) interviewProcessService.getVariable(candidateId, "qaHistory");
        if (qaHistory == null || qaHistory.isEmpty()) {
            log.warn("【简历面试Agent】问答历史为空，无法更新答案");
            return;
        }
        
        // 更新最后一个问题的答案（问题已在生成时保存，这里只需要更新答案）
        QAPair lastQAPair = qaHistory.get(qaHistory.size() - 1);
        if (lastQAPair != null && lastQAPair.answer == null) {
            lastQAPair.answer = lastAnswer;
            interviewProcessService.setVariable(candidateId, "qaHistory", qaHistory);
            log.info("【简历面试Agent】答案已更新到历史，当前历史数: {}", qaHistory.size());
        } else {
            log.warn("【简历面试Agent】最后一个问题已有答案，跳过更新");
        }
    }

    /**
     * 根据阶段生成问题
     */
    private void generateQuestionByStage(String candidateId,
                                        DelegateExecution execution,
                                        String stage,
                                        SseEmitter emitter) {
        // 处理INIT阶段：直接返回自我介绍问题
        if ("INIT".equals(stage) || stage == null || "GENERATE_QUESTION".equals(stage)) {
            // 如果是第一次（问题数为0），返回自我介绍问题
            Integer questionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
            if (questionCount == null || questionCount == 0) {
                try {
                    String introQuestion = SELF_INTRO_QUESTION;
                    interviewProcessService.setVariable(candidateId, "lastQuestion", introQuestion);
                    
                    questionCount = 1;
                    interviewProcessService.setVariable(candidateId, "questionCount", questionCount);
                    interviewProcessService.setVariable(candidateId, "currentStage", "GENERATE_QUESTION");
                    // 设置默认决策：继续面试（第一次问题后应该继续）
                    interviewProcessService.setVariable(candidateId, "shouldContinue", true);
                    interviewProcessService.setVariable(candidateId, "shouldEnd", false);
                    
                    // 立即将自我介绍问题保存到问答历史
                    @SuppressWarnings("unchecked")
                    List<QAPair> qaHistory = (List<QAPair>) interviewProcessService.getVariable(candidateId, "qaHistory");
                    if (qaHistory == null) {
                        qaHistory = new ArrayList<>();
                    }
                    QAPair introQAPair = new QAPair();
                    introQAPair.question = introQuestion;
                    introQAPair.answer = null; // 答案待用户提交
                    introQAPair.timestamp = System.currentTimeMillis();
                    qaHistory.add(introQAPair);
                    interviewProcessService.setVariable(candidateId, "qaHistory", qaHistory);
                    log.info("【简历面试Agent】自我介绍问题已保存到历史");
                    
                    // 流式发送自我介绍问题
                    emitter.send(SseEmitter.event().name("question")
                        .data("{\"content\":\"" + escapeJson(introQuestion) + "\",\"finished\":false}"));
                    emitter.send(SseEmitter.event().name("question")
                        .data("{\"content\":\"\",\"finished\":true,\"questionIndex\":" + questionCount + "}"));
                    emitter.complete();
                    log.info("【简历面试Agent】发送自我介绍问题，第{}题: {}", questionCount, introQuestion);
                    return;
                } catch (Exception e) {
                    log.error("【简历面试Agent】发送自我介绍问题失败", e);
                    sendError(emitter, "发送自我介绍问题失败: " + e.getMessage());
                    return;
                }
            }
        }
        
        // ========== 代码层面兜底保护（仅保留硬性上限）==========
        Integer currentQuestionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
        
        // 硬性上限：达到最大题数，强制结束（兜底保护，防止无限提问）
        if (currentQuestionCount != null && currentQuestionCount >= MAX_QUESTION_COUNT) {
            log.warn("【简历面试Agent】问题数已达到硬性上限（{}题），强制结束面试（兜底保护）", currentQuestionCount);
            forceEndInterview(candidateId, emitter, "问题数已达到上限（" + currentQuestionCount + "题），面试已完成。");
            return;
        }
        
        // 其他决策全部交给LLM通过Prompt来智能判断
        log.info("【简历面试Agent】当前题数: {}，由LLM根据实际情况智能决策是否继续", currentQuestionCount);
        
        // 构建Prompt变量（从流程变量中读取，而不是从execution）
        Map<String, Object> variables = buildPromptVariablesFromProcess(candidateId, stage);
        Prompt prompt = resumeBasedInterviewTemplate.create(variables);

        StringBuilder fullQuestion = new StringBuilder();
        interviewProcessService.setVariable(candidateId, "lastQuestion", null); // 重置

        chatModel.stream(prompt).subscribe(
            response -> {
                String chunk = response.getResult().getOutput().getText();
                if (chunk != null && !chunk.isEmpty()) {
                    fullQuestion.append(chunk);
                    try {
                        emitter.send(SseEmitter.event().name("question")
                            .data("{\"content\":\"" + escapeJson(chunk) + "\",\"finished\":false}"));
                    } catch (IOException e) {
                        log.error("【简历面试Agent】发送数据失败", e);
                    }
                }
            },
            error -> {
                log.error("【简历面试Agent】AI流式输出异常", error);
                try {
                    retryGenerateQuestion(candidateId, candidateId, stage, emitter);
                } catch (Exception e) {
                    log.error("【简历面试Agent】处理AI异常时出错", e);
                    sendError(emitter, "AI服务异常: " + error.getMessage());
                }
            },
            () -> {
                try {
                    String fullResponse = fullQuestion.toString().trim();
                    
                    // 检查响应是否为空
                    if (fullResponse == null || fullResponse.isEmpty()) {
                        log.warn("【简历面试Agent】生成的响应为空，使用默认问题");
                        retryGenerateQuestion(candidateId, candidateId, stage, emitter);
                        return;
                    }
                    
                    // 解析JSON响应
                    QuestionResponse questionResponse = parseQuestionResponse(fullResponse);
                    
                    // ========== LLM决策（主要决策机制）==========
                    // 重新获取当前题数（可能在LLM生成问题期间有变化）
                    Integer finalQuestionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
                    
                    // 代码层面最终验证：如果达到硬性上限，强制结束（兜底保护，覆盖LLM决策）
                    if (finalQuestionCount != null && finalQuestionCount >= MAX_QUESTION_COUNT) {
                        log.warn("【简历面试Agent】代码层面兜底验证：达到硬性上限（{}题），强制结束（覆盖LLM决策）", finalQuestionCount);
                        forceEndInterview(candidateId, emitter, "问题数已达到上限，面试已完成。");
                        return;
                    }
                    
                    // LLM决策：如果action是evaluate，说明应该结束面试
                    if ("evaluate".equalsIgnoreCase(questionResponse.action)) {
                        // 完全信任LLM的决策（LLM已经通过Prompt考虑了题数、覆盖维度、回答质量等因素）
                        log.info("【简历面试Agent】LLM决策：结束面试，进入评估阶段（题数: {}）", finalQuestionCount);
                        interviewProcessService.setVariable(candidateId, "shouldEnd", true);
                        interviewProcessService.setVariable(candidateId, "shouldContinue", false);
                        interviewProcessService.setVariable(candidateId, "finished", true);
                        interviewProcessService.setVariable(candidateId, "currentStage", "EVALUATE_AND_FINISH");
                        
                        // 如果流程在waitForAnswer等待，需要触发流程继续执行到评估阶段
                        if (interviewProcessService.isWaitingForAnswer(candidateId)) {
                            // 触发流程继续执行（会进入decisionGateway，然后到evaluateTask）
                            interviewProcessService.continueProcess(candidateId, "");
                        }
                        
                        emitter.send(SseEmitter.event().name("question")
                            .data("{\"content\":\"[面试结束] 感谢您的回答，面试已完成。\",\"finished\":true,\"isEnd\":true}"));
                        emitter.complete();
                        return;
                    }
                    
                    // LLM决策：继续面试
                    // 注意：必须在问题生成时设置这些变量，因为流程会在用户提交答案后立即到达decisionGateway
                    interviewProcessService.setVariable(candidateId, "shouldEnd", false);
                    interviewProcessService.setVariable(candidateId, "shouldContinue", true);
                    
                    log.info("【简历面试Agent】LLM决策：继续面试，已设置shouldContinue=true, shouldEnd=false");
                    
                    // 提取问题
                    String finalQuestion = questionResponse.question;
                    if (finalQuestion == null || finalQuestion.trim().isEmpty()) {
                        log.warn("【简历面试Agent】解析的问题为空，使用默认问题");
                        retryGenerateQuestion(candidateId, candidateId, stage, emitter);
                        return;
                    }
                    
                    // 使用解析出的问题
                    interviewProcessService.setVariable(candidateId, "lastQuestion", finalQuestion);
                    
                    Integer questionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
                    if (questionCount == null) {
                        questionCount = 0;
                    }
                    questionCount++;
                    interviewProcessService.setVariable(candidateId, "questionCount", questionCount);
                    
                    // 立即将问题保存到问答历史（不等待答案）
                    @SuppressWarnings("unchecked")
                    List<QAPair> qaHistory = (List<QAPair>) interviewProcessService.getVariable(candidateId, "qaHistory");
                    if (qaHistory == null) {
                        qaHistory = new ArrayList<>();
                    }
                    QAPair newQAPair = new QAPair();
                    newQAPair.question = finalQuestion;
                    newQAPair.answer = null; // 答案待用户提交
                    newQAPair.timestamp = System.currentTimeMillis();
                    qaHistory.add(newQAPair);
                    interviewProcessService.setVariable(candidateId, "qaHistory", qaHistory);
                    log.info("【简历面试Agent】问题已保存到历史，当前历史数: {}", qaHistory.size());
                    
                    // 更新技术维度覆盖情况
                    if (questionResponse.dimension != null && !questionResponse.dimension.trim().isEmpty()) {
                        @SuppressWarnings("unchecked")
                        List<String> coveredDimensionsList = (List<String>) interviewProcessService.getVariable(candidateId, "coveredTechDimensions");
                        if (coveredDimensionsList == null) {
                            coveredDimensionsList = new ArrayList<>();
                        }
                        String dimension = questionResponse.dimension.trim();
                        if (!coveredDimensionsList.contains(dimension)) {
                            coveredDimensionsList.add(dimension);
                        }
                        interviewProcessService.setVariable(candidateId, "coveredTechDimensions", coveredDimensionsList);
                        log.info("【简历面试Agent】更新技术维度: {}, 备注: {}", dimension, questionResponse.notes);
                    }
                    
                    log.info("【简历面试Agent】生成问题成功，第{}题: {}", questionCount, finalQuestion);
                    emitter.send(SseEmitter.event().name("question")
                        .data("{\"content\":\"\",\"finished\":true,\"questionIndex\":" + questionCount + "}"));
                    emitter.complete();
                    
                    // 问题生成完成后，触发流程继续执行（从waitForAnswer继续到decisionGateway）
                    // 注意：这里不立即触发，因为问题已经发送给用户，等待用户回答
                    // 流程会在用户提交答案后通过continueProcess触发
                } catch (IllegalStateException e) {
                    log.warn("【简历面试Agent】Emitter状态异常（可能已关闭）: {}", e.getMessage());
                } catch (IOException e) {
                    log.error("【简历面试Agent】发送结束标记失败", e);
                    try {
                        sendError(emitter, "发送问题失败: " + e.getMessage());
                    } catch (Exception ex) {
                        log.error("【简历面试Agent】发送错误消息也失败", ex);
                    }
                } catch (Exception e) {
                    log.error("【简历面试Agent】处理问题失败", e);
                    try {
                        sendError(emitter, "处理问题失败: " + e.getMessage());
                    } catch (Exception ex) {
                        log.error("【简历面试Agent】发送错误消息也失败", ex);
                    }
                }
            }
        );
    }

    /**
     * 构建Prompt变量（从流程变量中读取）
     */
    private Map<String, Object> buildPromptVariablesFromProcess(String candidateId, String stage) {
        Map<String, Object> variables = new HashMap<>();
        
        // 从流程变量中获取信息
        String jobName = (String) interviewProcessService.getVariable(candidateId, "jobName");
        String resume = (String) interviewProcessService.getVariable(candidateId, "resume");
        String jd = (String) interviewProcessService.getVariable(candidateId, "jd");
        @SuppressWarnings("unchecked")
        List<String> coveredDimensions = (List<String>) interviewProcessService.getVariable(candidateId, "coveredTechDimensions");
        @SuppressWarnings("unchecked")
        List<String> jdTechDimensions = (List<String>) interviewProcessService.getVariable(candidateId, "jdTechDimensions");
        Integer questionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
        @SuppressWarnings("unchecked")
        List<QAPair> qaHistory = (List<QAPair>) interviewProcessService.getVariable(candidateId, "qaHistory");
        
        // 岗位名称
        variables.put("job_title", jobName != null && !jobName.isEmpty() ? jobName : "技术");
        
        // 简历摘要（截取前500字）
        String resumeSummary = resume != null ? resume : "未提供简历";
        if (resumeSummary.length() > 500) {
            resumeSummary = resumeSummary.substring(0, 500) + "...";
        }
        variables.put("resume_summary", resumeSummary);
        
        // JD核心要求（截取前500字）
        String jdRequirements = jd != null ? jd : "未提供职位要求";
        if (jdRequirements.length() > 500) {
            jdRequirements = jdRequirements.substring(0, 500) + "...";
        }
        variables.put("jd_requirements", jdRequirements);
        
        // 当前状态
        variables.put("current_state", stage);
        
        // 已覆盖技术维度
        String coveredDimensionsStr = coveredDimensions != null && !coveredDimensions.isEmpty() 
                ? String.join("、", coveredDimensions) : "（暂无）";
        variables.put("covered_dimensions", coveredDimensionsStr);
        
        // 待考察的JD技术维度（未覆盖的）
        List<String> uncoveredDimensions = new ArrayList<>();
        if (jdTechDimensions != null && coveredDimensions != null) {
            for (String jdDim : jdTechDimensions) {
                if (!coveredDimensions.contains(jdDim)) {
                    uncoveredDimensions.add(jdDim);
                }
            }
        } else if (jdTechDimensions != null) {
            uncoveredDimensions.addAll(jdTechDimensions);
        }
        String uncoveredDimensionsStr = !uncoveredDimensions.isEmpty() 
                ? String.join("、", uncoveredDimensions) : "（已全部覆盖）";
        variables.put("uncovered_dimensions", uncoveredDimensionsStr);
        
        // 根据阶段设置任务描述
        String taskDescription = getTaskDescriptionByStage(candidateId, stage, coveredDimensionsStr, uncoveredDimensionsStr);
        variables.put("task_description", taskDescription);
        
        // 构建问答历史字符串（传递给LLM，避免重复提问）
        StringBuilder qaHistoryStr = new StringBuilder();
        if (qaHistory != null && !qaHistory.isEmpty()) {
            qaHistoryStr.append("## 已问过的问题（绝对禁止重复）：\n\n");
            for (int i = 0; i < qaHistory.size(); i++) {
                QAPair qa = qaHistory.get(i);
                qaHistoryStr.append("问题").append(i + 1).append(": ").append(qa.question != null ? qa.question : "").append("\n");
                if (qa.answer != null && !qa.answer.trim().isEmpty()) {
                    // 答案摘要（避免太长）
                    String answerSummary = qa.answer.length() > 200 
                        ? qa.answer.substring(0, 200) + "..." 
                        : qa.answer;
                    qaHistoryStr.append("回答: ").append(answerSummary).append("\n");
                }
                qaHistoryStr.append("\n");
            }
            qaHistoryStr.append("**重要**：以上问题已经问过，绝对不能重复提问，即使是相似的问题也不行！\n");
        } else {
            qaHistoryStr.append("（暂无已问问题）\n");
        }
        variables.put("qa_history", qaHistoryStr.toString());
        
        // 问题数
        variables.put("question_count", questionCount != null ? questionCount : 0);
        
        log.info("【简历面试Agent】Prompt变量 - 岗位: {}, 阶段: {}, 已覆盖: {}, 待考察: {}, 已问问题数: {}", 
                jobName, stage, coveredDimensionsStr, uncoveredDimensionsStr, questionCount);

        return variables;
    }
    
    /**
     * 根据阶段获取任务描述
     * 统一使用LLM决策，不再区分简历初探和JD对齐阶段
     */
    private String getTaskDescriptionByStage(String candidateId, String stage, String coveredDimensions, String uncoveredDimensions) {
        Integer questionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
        
        // 根据问题数动态调整任务描述
        if (questionCount != null && questionCount <= 2) {
            // 前2题：简历初探
            return "请从简历中选择 1 个关键技术点（如框架、工具、项目），提出一个开放性问题，引导候选人展开说明。\n" +
                   "例如：\"你在项目中使用了 Redis，能讲讲缓存策略和穿透解决方案吗？\"";
        } else {
            // 后续题目：JD对齐深挖
            return "已考察的技术维度：" + coveredDimensions + "\n" +
                   "待考察的核心 JD 要求：" + uncoveredDimensions + "\n\n" +
                   "请选择一个**未覆盖的 JD 要求**，结合候选人简历中的相关经验，提出一个**深入、具体的技术问题**。\n" +
                   "- 问题必须可验证技术深度（避免\"是否用过\"这类是非题）\n" +
                   "- 如果某维度简历中未体现，可跳过，但需记录\"无相关经验\"\n\n" +
                   "**重要**：根据当前面试进度和回答质量，判断是否应该结束面试。如果已问足够问题（≥5题）且已覆盖主要技术维度，可以返回 action=\"evaluate\" 来结束面试。";
        }
    }

    /**
     * 重试生成问题（使用默认问题）
     */
    private void retryGenerateQuestion(String candidateId,
                                       String candidateIdParam,
                                       String stage,
                                       SseEmitter emitter) {
        log.warn("【简历面试Agent】使用默认问题");
        try {
            String resume = (String) interviewProcessService.getVariable(candidateId, "resume");
            String resumeSnippet = (resume == null) ? "" : resume.substring(0, Math.min(50, resume.length()));
            String defaultQuestion = "请直接回答：结合你的简历，挑一个你最有把握的技术点，说明原理、你做过的实践以及踩过的坑。";
            if (!resumeSnippet.isEmpty()) {
                defaultQuestion = "请直接回答：结合你简历中的\"" + resumeSnippet + "\"，挑一个你最有把握的技术点，说明原理、你做过的实践以及踩过的坑。";
            }
            
            interviewProcessService.setVariable(candidateId, "lastQuestion", defaultQuestion);
            
            Integer questionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
            if (questionCount == null) {
                questionCount = 0;
            }
            questionCount++;
            interviewProcessService.setVariable(candidateId, "questionCount", questionCount);
            
            // 先发送完整的问题内容
            emitter.send(SseEmitter.event().name("question")
                .data("{\"content\":\"" + escapeJson(defaultQuestion) + "\",\"finished\":false}"));
            // 再发送结束标记
            emitter.send(SseEmitter.event().name("question")
                .data("{\"content\":\"\",\"finished\":true,\"questionIndex\":" + questionCount + "}"));
            emitter.complete();
            log.info("【简历面试Agent】默认问题发送成功，第{}题: {}", questionCount, defaultQuestion);
        } catch (IllegalStateException e) {
            log.warn("【简历面试Agent】Emitter状态异常（可能已关闭），无法发送默认问题: {}", e.getMessage());
        } catch (Exception e) {
            log.error("【简历面试Agent】发送默认问题失败", e);
            try {
                sendError(emitter, "生成问题失败，请重试");
            } catch (Exception ex) {
                log.error("【简历面试Agent】发送错误消息也失败", ex);
            }
        }
    }

    /**
     * 清理面试上下文
     */
    public void clearInterviewContext(String candidateId) {
        stringRedisTemplate.delete(REDIS_AGENT_CONTEXT_PREFIX + candidateId);
        workflowContext.removeExecution(candidateId);
    }

    /**
     * 获取当前状态（兼容性方法）
     */
    public String getCurrentState(String candidateId) {
        String stage = (String) interviewProcessService.getVariable(candidateId, "currentStage");
        return stage != null ? stage : "INIT";
    }

    /**
     * 获取问答历史（用于提交分析）
     */
    @SuppressWarnings("unchecked")
    public List<QAPair> getQaHistory(String candidateId) {
        List<QAPair> qaHistory = (List<QAPair>) interviewProcessService.getVariable(candidateId, "qaHistory");
        return qaHistory != null ? qaHistory : new ArrayList<>();
    }

    /**
     * 获取面试上下文信息（用于提交分析）
     */
    public ResumeBasedInterviewContext getInterviewContextForSubmit(String candidateId) {
        ProcessInstance processInstance = interviewProcessService.getProcessInstance(candidateId);
        if (processInstance == null) {
            return null;
        }
        
        ResumeBasedInterviewContext context = new ResumeBasedInterviewContext();
        context.candidateId = (String) interviewProcessService.getVariable(candidateId, "candidateId");
        context.jobId = (String) interviewProcessService.getVariable(candidateId, "jobId");
        context.jobName = (String) interviewProcessService.getVariable(candidateId, "jobName");
        context.resume = (String) interviewProcessService.getVariable(candidateId, "resume");
        context.jd = (String) interviewProcessService.getVariable(candidateId, "jd");
        context.currentState = (String) interviewProcessService.getVariable(candidateId, "currentStage");
        context.questionCount = (Integer) interviewProcessService.getVariable(candidateId, "questionCount");
        @SuppressWarnings("unchecked")
        List<String> coveredDimensions = (List<String>) interviewProcessService.getVariable(candidateId, "coveredTechDimensions");
        context.coveredTechDimensions = coveredDimensions != null ? coveredDimensions : new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String> jdTechDimensions = (List<String>) interviewProcessService.getVariable(candidateId, "jdTechDimensions");
        context.jdTechDimensions = jdTechDimensions != null ? jdTechDimensions : new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<QAPair> qaHistory = (List<QAPair>) interviewProcessService.getVariable(candidateId, "qaHistory");
        context.qaHistory = qaHistory != null ? qaHistory : new ArrayList<>();
        context.lastQuestion = (String) interviewProcessService.getVariable(candidateId, "lastQuestion");
        
        return context;
    }

    private void sendError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("error").data("{\"error\":\"" + escapeJson(msg) + "\"}"));
            emitter.completeWithError(new RuntimeException(msg));
        } catch (IOException e) {
            log.error("发送错误消息失败", e);
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 解析问题响应（JSON格式）
     */
    private QuestionResponse parseQuestionResponse(String response) {
        try {
            String jsonStr = response.trim();
            
            // 提取JSON对象（从第一个 { 到最后一个 }）
            int start = jsonStr.indexOf('{');
            int end = jsonStr.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end + 1);
            }
            
            // 解析JSON
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = JsonUtils.jsonToPojo(jsonStr, HashMap.class);
            
            String action = responseMap.getOrDefault("action", "ask").toString();
            String question = responseMap.getOrDefault("question", "").toString();
            String dimension = responseMap.getOrDefault("dimension", "").toString();
            String notes = responseMap.getOrDefault("notes", "").toString();
            
            return new QuestionResponse(action, question, dimension, notes);
        } catch (Exception e) {
            log.error("【简历面试Agent】解析JSON响应失败: {}", e.getMessage());
            return new QuestionResponse("ask", response.trim(), "", "");
        }
    }
    
    /**
     * 问题响应数据结构
     */
    private static class QuestionResponse {
        String action;      // "ask" 或 "evaluate"
        String question;   // 问题内容
        String dimension;   // 技术维度
        String notes;      // 备注
        
        QuestionResponse(String action, String question, String dimension, String notes) {
            this.action = action;
            this.question = question;
            this.dimension = dimension;
            this.notes = notes;
        }
    }

    /**
     * 简历面试上下文数据结构
     */
    public static class ResumeBasedInterviewContext {
        public String candidateId;
        public String jobId;
        public String jobName;
        public String resume;
        public String jd;
        public String currentState;
        public int questionCount;
        public List<String> coveredTechDimensions;
        public List<String> jdTechDimensions;  // JD中的技术维度列表
        public List<QAPair> qaHistory;
        public String lastQuestion;
    }

    /**
     * 问答对
     * 必须实现Serializable接口，才能作为Flowable流程变量存储
     */
    public static class QAPair implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        
        public String question;
        public String answer;
        public long timestamp;
    }
}
