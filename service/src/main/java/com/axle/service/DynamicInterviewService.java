package com.axle.service;

import com.axle.pojo.Job;
import com.axle.utils.JsonUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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
 * 动态提问服务
 * 使用 PromptTemplate 管理提示词，专注于流式面试题目生成
 */
@Service
@Slf4j
public class DynamicInterviewService {

    @Autowired
    @Qualifier("zhipuAiChatModel")
    private ChatModel qwenModel;

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private JobService jobService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ResourceLoader resourceLoader;

    private PromptTemplate dynamicInterviewTemplate;

    private static final String REDIS_INTERVIEW_CONTEXT_PREFIX = "interview:context:";
    private static final String REDIS_ASKED_QUESTIONS_PREFIX = "interview:questions:";
    private static final int CONTEXT_EXPIRE_HOURS = 24;
    private static final int MAX_QUESTIONS = 10;

    @PostConstruct
    public void init() {
        log.info("【动态面试】加载 Prompt 模板...");
        org.springframework.core.io.Resource resource = resourceLoader.getResource("classpath:newprompts/dynamicinterview.st");
        this.dynamicInterviewTemplate = new PromptTemplate(resource);
    }

    /**
     * 初始化面试上下文
     */
    public void initInterviewContext(String candidateId, String jobId) {
        log.info("【动态面试】初始化上下文，候选人ID: {}, 职位ID: {}", candidateId, jobId);

        String resume = resumeService.getResume(candidateId);
        boolean hasResumeInRedis = resumeService.hasResume(candidateId);
        log.info("【动态面试】从Redis读取简历，候选人ID: {}, hasResume方法返回: {}, getResume返回: {}, 简历长度: {}", 
                candidateId, hasResumeInRedis, resume != null, resume != null ? resume.length() : 0);
        
        if (resume == null || resume.isEmpty()) {
            log.warn("【动态面试】简历为空或null，hasResume={}，使用默认提示", hasResumeInRedis);
            resume = "候选人未提供简历，请基于通用技术面试提问。";
        }
        
        String jd = "";
        try {
            Job job = jobService.getDetail(jobId);
            jd = (job != null && job.getJobDesc() != null) ? job.getJobDesc() : "";
            log.info("【动态面试】获取JD成功，JD长度: {}", jd != null ? jd.length() : 0);
        } catch (Exception e) {
            log.warn("【动态面试】获取JD失败: {}", e.getMessage(), e);
        }

        String contextKey = REDIS_INTERVIEW_CONTEXT_PREFIX + candidateId;
        InterviewContext context = new InterviewContext();
        context.resume = resume;
        context.jd = jd;
        context.questionCount = 0;
        
        log.info("【动态面试】准备序列化上下文，resume长度: {}, jd长度: {}", 
                context.resume != null ? context.resume.length() : 0,
                context.jd != null ? context.jd.length() : 0);
        
        String contextJson = JsonUtils.objectToJson(context);
        if (contextJson == null || contextJson.isEmpty()) {
            log.error("【动态面试】JSON序列化失败，context: resume长度={}, jd长度={}", 
                    resume != null ? resume.length() : 0, jd != null ? jd.length() : 0);
            throw new RuntimeException("初始化面试上下文失败：JSON序列化异常");
        }
        log.info("【动态面试】JSON序列化成功，JSON长度: {}", contextJson.length());
        
        try {
            stringRedisTemplate.opsForValue().set(contextKey, contextJson, CONTEXT_EXPIRE_HOURS, TimeUnit.HOURS);
            // 验证是否保存成功
            String savedJson = stringRedisTemplate.opsForValue().get(contextKey);
            if (savedJson == null || !savedJson.equals(contextJson)) {
                log.error("【动态面试】Redis保存验证失败，期望长度: {}, 实际长度: {}", 
                        contextJson.length(), savedJson != null ? savedJson.length() : 0);
                throw new RuntimeException("初始化面试上下文失败：Redis保存验证失败");
            }
            log.info("【动态面试】上下文已保存到Redis并验证成功，key: {}", contextKey);
        } catch (Exception e) {
            log.error("【动态面试】Redis保存失败", e);
            throw new RuntimeException("初始化面试上下文失败：Redis保存异常 - " + e.getMessage(), e);
        }

        stringRedisTemplate.opsForValue().set(REDIS_ASKED_QUESTIONS_PREFIX + candidateId, "[]", CONTEXT_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 流式生成面试题
     */
    public void generateQuestionStream(String candidateId, String lastAnswer, String lastQuestion, SseEmitter emitter) {
        log.info("【动态面试-流式】开始生成问题，候选人ID: {}, lastQuestion: {}, lastAnswer: {}", 
                candidateId, lastQuestion != null ? "有" : "无", lastAnswer != null ? "有" : "无");
        
        InterviewContext context = getInterviewContext(candidateId);
        if (context == null) {
            log.error("【动态面试-流式】上下文为空，候选人ID: {}", candidateId);
            sendError(emitter, "面试上下文不存在，请先上传简历");
            return;
        }
        
        log.info("【动态面试-流式】上下文获取成功，简历长度: {}, JD长度: {}", 
                context.resume != null ? context.resume.length() : 0,
                context.jd != null ? context.jd.length() : 0);

        List<String> askedQuestions = getAskedQuestions(candidateId);

        if (lastAnswer != null && !lastAnswer.isEmpty() && lastQuestion != null && !lastQuestion.isEmpty()) {
            addAnswerToContext(candidateId, lastQuestion, lastAnswer);
        }

        if (askedQuestions.size() >= MAX_QUESTIONS) {
            try {
                emitter.send(SseEmitter.event().name("question")
                    .data("{\"content\":\"[面试结束] 感谢您的回答，面试已完成。\",\"finished\":true,\"isEnd\":true}"));
                emitter.complete();
            } catch (IOException e) {
                log.error("【动态面试-流式】发送结束消息失败", e);
            }
            return;
        }

        // 使用 PromptTemplate 构建提示词
        String askedQuestionsSection = "";
        if (!askedQuestions.isEmpty()) {
            StringBuilder sb = new StringBuilder("## 已问过的问题及主题（这些主题都不能再问）\n");
            for (int i = 0; i < askedQuestions.size(); i++) {
                sb.append(i + 1).append(". ").append(askedQuestions.get(i)).append("\n");
            }
            sb.append("\n## 已覆盖的技术主题（禁止再问）\n");
            sb.append("从上述问题中提取的主题都不能再问，包括其变体和深入追问。\n");
            askedQuestionsSection = sb.toString();
        }

        String taskSection = "";
        if (lastQuestion == null) {
            taskSection = "## 任务\n请根据简历和JD，生成第一道面试题。要求针对技术栈或项目经验，只输出问题本身，不超过100字。";
        } else {
            taskSection = String.format("## 上一题及回答\n问题: %s\n回答: %s\n\n## 任务\n请选择一个【全新的技术主题】提问，必须与已问过的所有问题主题完全不同。只输出问题本身，不超过100字。", 
                    lastQuestion, lastAnswer);
        }

        Map<String, Object> variables = Map.of(
                "resume", context.resume,
                "jd", context.jd,
                "asked_questions_section", askedQuestionsSection,
                "task_section", taskSection
        );

        Prompt aiPrompt = dynamicInterviewTemplate.create(variables);
        log.info("【动态面试-流式】Prompt已构建，开始调用AI模型");
        StringBuilder fullQuestion = new StringBuilder();

        qwenModel.stream(aiPrompt).subscribe(
            response -> {
                String chunk = response.getResult().getOutput().getText();
                if (chunk != null && !chunk.isEmpty()) {
                    fullQuestion.append(chunk);
                    try {
                        emitter.send(SseEmitter.event().name("question")
                            .data("{\"content\":\"" + escapeJson(chunk) + "\",\"finished\":false}"));
                    } catch (IOException e) {
                        log.error("【动态面试-流式】发送数据失败", e);
                    }
                }
            },
            error -> {
                log.error("【动态面试-流式】AI流式输出异常", error);
                sendError(emitter, "AI服务异常: " + error.getMessage());
            },
            () -> {
                try {
                    String finalQuestion = fullQuestion.toString().trim();
                    List<String> questions = getAskedQuestions(candidateId);
                    questions.add(finalQuestion);
                    saveAskedQuestions(candidateId, questions);
                    updateQuestionCount(candidateId);

                    emitter.send(SseEmitter.event().name("question")
                        .data("{\"content\":\"\",\"finished\":true,\"questionIndex\":" + questions.size() + "}"));
                    emitter.complete();
                } catch (IOException e) {
                    log.error("【动态面试-流式】发送结束标记失败", e);
                }
            }
        );
    }

    private void addAnswerToContext(String candidateId, String question, String answer) {
        log.info("【动态面试】记录问答历史: Q={}, A={}", question, answer);
    }

    private InterviewContext getInterviewContext(String candidateId) {
        String json = stringRedisTemplate.opsForValue().get(REDIS_INTERVIEW_CONTEXT_PREFIX + candidateId);
        if (json == null || json.isEmpty() || "null".equals(json)) {
            log.warn("【动态面试】Redis上下文不存在或为null，候选人ID: {}, JSON值: {}", candidateId, json);
            return null;
        }
        
        try {
            InterviewContext context = JsonUtils.jsonToPojo(json, InterviewContext.class);
            if (context == null) {
                log.error("【动态面试】JSON反序列化返回null，JSON: {}", json);
                return null;
            }
            log.debug("【动态面试】成功读取上下文，简历长度: {}, JD长度: {}, 问题数: {}", 
                    context.resume != null ? context.resume.length() : 0,
                    context.jd != null ? context.jd.length() : 0,
                    context.questionCount);
            return context;
        } catch (Exception e) {
            log.error("【动态面试】解析Redis上下文失败，JSON长度: {}, 错误: {}", 
                    json != null ? json.length() : 0, e.getMessage(), e);
            return null;
        }
    }

    private void saveAskedQuestions(String candidateId, List<String> questions) {
        String json = JsonUtils.objectToJson(questions);
        if (json == null || json.isEmpty()) {
            log.error("【动态面试】问题列表JSON序列化失败，questions size: {}", questions != null ? questions.size() : 0);
            // 如果序列化失败，使用空数组作为兜底
            json = "[]";
        }
        stringRedisTemplate.opsForValue().set(REDIS_ASKED_QUESTIONS_PREFIX + candidateId, json, CONTEXT_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    private List<String> getAskedQuestions(String candidateId) {
        String json = stringRedisTemplate.opsForValue().get(REDIS_ASKED_QUESTIONS_PREFIX + candidateId);
        if (json == null || json.isEmpty() || json.equals("[]")) {
            return new ArrayList<>();
        }
        
        try {
            List<String> list = JsonUtils.jsonToList(json, String.class);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.error("【动态面试】解析已问问题列表失败，JSON: {}, 错误: {}", json, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private void updateQuestionCount(String candidateId) {
        String key = REDIS_INTERVIEW_CONTEXT_PREFIX + candidateId;
        InterviewContext ctx = getInterviewContext(candidateId);
        if (ctx != null) {
            ctx.questionCount++;
            String json = JsonUtils.objectToJson(ctx);
            if (json == null || json.isEmpty()) {
                log.error("【动态面试】更新问题数量时JSON序列化失败，questionCount: {}", ctx.questionCount);
                return;
            }
            stringRedisTemplate.opsForValue().set(key, json, CONTEXT_EXPIRE_HOURS, TimeUnit.HOURS);
        }
    }

    public void clearInterviewContext(String candidateId) {
        stringRedisTemplate.delete(REDIS_INTERVIEW_CONTEXT_PREFIX + candidateId);
        stringRedisTemplate.delete(REDIS_ASKED_QUESTIONS_PREFIX + candidateId);
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

    private String unescapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * 面试上下文数据结构（用于 JSON 序列化）
     */
    public static class InterviewContext {
        private String resume;
        private String jd;
        private int questionCount;

        // Jackson 需要无参构造函数和 getter/setter
        public InterviewContext() {}

        public String getResume() {
            return resume;
        }

        public void setResume(String resume) {
            this.resume = resume;
        }

        public String getJd() {
            return jd;
        }

        public void setJd(String jd) {
            this.jd = jd;
        }

        public int getQuestionCount() {
            return questionCount;
        }

        public void setQuestionCount(int questionCount) {
            this.questionCount = questionCount;
        }
    }
}
