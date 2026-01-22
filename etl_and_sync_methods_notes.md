 Spring AI ETL 与同步/流式处理 学习笔记

本笔记记录了项目中从旧版本到新版本的架构演进，包含 ETL 的多种实现方式以及流式/同步接口的对比。

---

## 1. 简历 ETL 的多种写法

### 写法 A：AI 增强型清洗（旧版写法）
**特点**：使用大模型识别字段，逻辑强大但速度慢（3-10秒）。

```java
// 使用 AI 进行清洗
public String cleanResumeWithAI(String rawText) {
    String prompt = "请从以下文本中移除隐私信息，并整理出工作经历、项目经历和技术栈：\n" + rawText;
    // 调用大模型
    return chatModel.call(prompt); 
}
```

### 写法 B：手动轻量化 ETL（规则清洗）
**特点**：基于正则表达式和关键词过滤，响应极快（<100ms）。

```java
// Extract: 传统方式读取文件
InputStream is = file.getInputStream();
// Transform: 手动正则过滤
String cleaned = rawText.replaceAll("1[3-9]\\d{9}", "[手机号已隐藏]");
// Load: 存入 Redis
redisTemplate.opsForValue().set(key, cleaned);
```

### 写法 C：标准 Spring AI ETL（当前采用）
**特点**：使用 Spring AI 提供的 `DocumentReader` 和 `Transformer` 接口，架构优雅，自动支持多种格式。

```java
// 1. Extract (使用 Tika 自动识别 PDF/Word)
TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(file.getInputStream()));
List<Document> documents = reader.get();

// 2. Transform (可以结合自定义 Transformer)
// List<Document> transformed = myTransformer.transform(documents);

// 3. Load (将处理后的文本持久化)
String content = documents.stream().map(Document::getText).collect(Collectors.joining("\n"));
```

---

## 2. 动态提问：同步 vs 流式

### 2.1 同步调用 (Sync) - 已移除（Dynamic 旧实现之一）
**特点**：用户点击后需等待 5-8 秒才能看到完整结果，后端一次性返回完整问题。

```java
// ========== 控制器层 - 已删除的同步接口 ==========
@GetMapping("/startSync")
public GraceJSONResult startSync(@RequestParam("candidateId") String candidateId,
                                 @RequestParam("jobId") String jobId) {
    log.info("【动态面试-同步】开始面试，候选人ID: {}, 职位ID: {}", candidateId, jobId);
    
    try {
        // 初始化上下文
        dynamicInterviewService.initInterviewContext(candidateId, jobId);
        // 同步生成第一题
        String question = dynamicInterviewService.generateQuestion(candidateId, null, null);
        return GraceJSONResult.ok(question);
    } catch (Exception e) {
        log.error("【动态面试-同步】处理失败", e);
        return GraceJSONResult.errorMsg("生成问题失败: " + e.getMessage());
    }
}

@PostMapping("/nextSync")
public GraceJSONResult nextSync(@RequestParam("candidateId") String candidateId,
                               @RequestParam("lastQuestion") String lastQuestion,
                               @RequestParam("lastAnswer") String lastAnswer) {
    log.info("【动态面试-同步】获取下一题，候选人ID: {}", candidateId);
    
    try {
        String question = dynamicInterviewService.generateQuestion(candidateId, lastAnswer, lastQuestion);
        return GraceJSONResult.ok(question);
    } catch (Exception e) {
        log.error("【动态面试-同步】处理失败", e);
        return GraceJSONResult.errorMsg("生成问题失败: " + e.getMessage());
    }
}

// ========== 服务层 - 已删除的同步方法 ==========
/**
 * 同步生成面试题（完整版旧实现）
 * @param candidateId 候选人ID
 * @param lastAnswer 上一题答案（首次为null）
 * @param lastQuestion 上一题问题（首次为null）
 * @return 生成的问题文本
 */
public String generateQuestion(String candidateId, String lastAnswer, String lastQuestion) {
    // 1. 获取面试上下文
    InterviewContext context = getInterviewContext(candidateId);
    if (context == null) {
        throw new RuntimeException("面试上下文不存在，请先上传简历");
    }

    // 2. 获取已问过的问题列表
    List<String> askedQuestions = getAskedQuestions(candidateId);

    // 3. 如果已问过10题，返回结束提示
    if (askedQuestions.size() >= MAX_QUESTIONS) {
        return "[面试结束] 感谢您的回答，面试已完成。";
    }

    // 4. 如果有上一题和答案，记录到上下文
    if (lastAnswer != null && !lastAnswer.isEmpty() && lastQuestion != null && !lastQuestion.isEmpty()) {
        addAnswerToContext(candidateId, lastQuestion, lastAnswer);
    }

    // 5. 使用 StringBuilder 构建完整的 Prompt（旧写法）
    StringBuilder promptBuilder = new StringBuilder();
    promptBuilder.append("你是一位专业的技术面试官，需要根据候选人的简历和职位要求进行面试提问。\n\n");
    
    promptBuilder.append("## 候选人简历\n").append(context.resume).append("\n\n");
    promptBuilder.append("## 职位要求\n").append(context.jd).append("\n\n");

    // 已问过的问题
    if (!askedQuestions.isEmpty()) {
        promptBuilder.append("## 已问过的问题（不要重复提问）\n");
        for (int i = 0; i < askedQuestions.size(); i++) {
            promptBuilder.append(i + 1).append(". ").append(askedQuestions.get(i)).append("\n");
        }
        promptBuilder.append("\n");
    }

    // 任务说明
    if (lastQuestion == null) {
        promptBuilder.append("## 任务\n");
        promptBuilder.append("请根据简历和JD，生成第一道面试题。要求针对技术栈或项目经验，只输出问题本身，不超过100字。");
    } else {
        promptBuilder.append("## 上一题及回答\n");
        promptBuilder.append("问题: ").append(lastQuestion).append("\n");
        promptBuilder.append("回答: ").append(lastAnswer).append("\n\n");
        promptBuilder.append("## 任务\n");
        promptBuilder.append("请基于回答进行深度追问或转向新考点。只输出问题本身，不超过100字。");
    }

    // 6. 同步调用大模型（阻塞等待）
    Prompt prompt = new Prompt(promptBuilder.toString());
    String question = qwenModel.call(prompt).getResult().getOutput().getText();

    // 7. 保存已问过的问题
    askedQuestions.add(question);
    saveAskedQuestions(candidateId, askedQuestions);
    updateQuestionCount(candidateId);

    return question.trim();
}

// 辅助方法：获取面试上下文
private InterviewContext getInterviewContext(String candidateId) {
    String json = stringRedisTemplate.opsForValue().get(REDIS_INTERVIEW_CONTEXT_PREFIX + candidateId);
    if (json == null) return null;
    
    InterviewContext context = new InterviewContext();
    try {
        context.resume = extractJsonValue(json, "resume");
        context.jd = extractJsonValue(json, "jd");
        context.questionCount = Integer.parseInt(extractJsonValue(json, "questionCount"));
    } catch (Exception e) {
        log.error("解析Redis上下文失败", e);
    }
    return context;
}
```

### 2.2 同步 + 前端“伪流式”模拟（Dynamic 旧实现之二）
**特点**：后端仍是一次性返回完整问题，前端通过定时器逐字显示，模拟流式体验（主要用于 APP-PLUS 等不支持 SSE 的端）。

**后端实现**（已删除）：
```java
// Controller 中的同步接口返回完整问题
@GetMapping("/startSync")
public GraceJSONResult startSync(@RequestParam("candidateId") String candidateId,
                                 @RequestParam("jobId") String jobId) {
    // ... 调用 generateQuestion() 获取完整问题 ...
    String fullQuestion = dynamicInterviewService.generateQuestion(candidateId, null, null);
    return GraceJSONResult.ok(fullQuestion); // 一次性返回完整文本
}
```

**前端实现**（已删除，旧 `dynamic-interview-chat.vue`）：
```javascript
// APP-PLUS 平台：使用 uni.request 同步调用 + 前端模拟流式
methods: {
  async getNextQuestion() {
    if (this.platform === 'APP-PLUS') {
      // 同步 API 调用
      uni.request({
        url: `${this.baseUrl}/dynamicInterview/nextSync`,
        method: 'POST',
        data: {
          candidateId: this.candidateId,
          lastQuestion: this.currentQuestion,
          lastAnswer: this.currentAnswer
        },
        success: (res) => {
          const fullText = res.data.data; // 后端一次性返回的完整问题
          
          // 前端模拟流式：逐字显示
          this.isStreaming = true;
          this.currentQuestion = '';
          let index = 0;
          
          const timer = setInterval(() => {
            if (index >= fullText.length) {
              clearInterval(timer);
              this.isStreaming = false;
              return;
            }
            this.currentQuestion += fullText[index++];
          }, 40); // 每 40ms 吐一个字符，营造"流式"观感
        },
        fail: (err) => {
          uni.showToast({ title: '获取问题失败', icon: 'none' });
        }
      });
    } else {
      // H5 平台使用 EventSource 真正的 SSE 流式
      const eventSource = new EventSource(`${this.baseUrl}/dynamicInterview/next?...`);
      eventSource.onmessage = (event) => {
        const data = JSON.parse(event.data);
        this.currentQuestion += data.content;
        if (data.finished) {
          eventSource.close();
          this.isStreaming = false;
        }
      };
    }
  }
}
```

**问题**：
- 前端需要等待后端完整响应（5-8秒），首字延迟高
- 前端定时器模拟只是视觉效果，实际响应速度没有提升
- 代码冗余：需要为不同平台维护两套逻辑

### 2.3 真正的流式调用 (Streaming/SSE) - 当前采用
**特点**：实时吐字，响应时间 < 1秒，用户体验最佳。后端使用 `chatModel.stream()` 真正的流式输出，前端使用 `EventSource` 接收。

```java
// ========== 控制器层 - 当前流式实现 ==========
@GetMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter startInterview(@RequestParam("candidateId") String candidateId,
                                 @RequestParam("jobId") String jobId) {
    log.info("【动态面试-流式】开始面试，候选人ID: {}, 职位ID: {}", candidateId, jobId);
    
    SseEmitter emitter = new SseEmitter(120000L); // 120秒超时
    
    emitter.onCompletion(() -> log.info("【动态面试-流式】SSE连接完成"));
    emitter.onTimeout(() -> log.warn("【动态面试-流式】SSE连接超时"));
    emitter.onError(e -> log.error("【动态面试-流式】SSE连接错误", e));
    
    // 异步执行流式任务
    CompletableFuture.runAsync(() -> {
        try {
            dynamicInterviewService.initInterviewContext(candidateId, jobId);
            dynamicInterviewService.generateQuestionStream(candidateId, null, null, emitter);
        } catch (Exception e) {
            log.error("【动态面试-流式】处理失败", e);
            sendErrorMessage(emitter, e.getMessage());
            emitter.completeWithError(e);
        }
    });
    
    return emitter;
}

// ========== 服务层 - 当前流式实现 ==========
public void generateQuestionStream(String candidateId, String lastAnswer, String lastQuestion, SseEmitter emitter) {
    InterviewContext context = getInterviewContext(candidateId);
    if (context == null) {
        sendError(emitter, "面试上下文不存在，请先上传简历");
        return;
    }

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

    // 使用 PromptTemplate 构建提示词（不再用 StringBuilder 拼接）
    String askedQuestionsSection = "";
    if (!askedQuestions.isEmpty()) {
        StringBuilder sb = new StringBuilder("## 已问过的问题（不要重复提问）\n");
        for (int i = 0; i < askedQuestions.size(); i++) {
            sb.append(i + 1).append(". ").append(askedQuestions.get(i)).append("\n");
        }
        askedQuestionsSection = sb.toString();
    }

    String taskSection = "";
    if (lastQuestion == null) {
        taskSection = "## 任务\n请根据简历和JD，生成第一道面试题。要求针对技术栈或项目经验，只输出问题本身，不超过100字。";
    } else {
        taskSection = String.format("## 上一题及回答\n问题: %s\n回答: %s\n\n## 任务\n请基于回答进行深度追问或转向新考点。只输出问题本身，不超过100字。", 
                lastQuestion, lastAnswer);
    }

    Map<String, Object> variables = Map.of(
            "resume", context.resume,
            "jd", context.jd,
            "asked_questions_section", askedQuestionsSection,
            "task_section", taskSection
    );

    Prompt aiPrompt = dynamicInterviewTemplate.create(variables);
    StringBuilder fullQuestion = new StringBuilder();

    // 真正的流式调用：使用 chatModel.stream() 而不是 chatModel.call()
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
```

**关键区别对比**：
- **同步方法**：使用 `chatModel.call(prompt)`，阻塞等待完整响应（5-8秒），一次性返回字符串
- **流式方法**：使用 `chatModel.stream(prompt).subscribe(...)`，实时接收文本块，通过 SSE 逐字发送给前端（<1秒首字到达）

---

## 3. Prompt 管理的进阶写法

### 3.1 基础：StringBuilder 拼接（Dynamic 旧实现之一，不推荐）
下面是 Dynamic 面试中典型的旧写法：通过 `StringBuilder` 手动拼接简历、JD、已问过的问题和任务说明。

```java
public Prompt buildOldStylePrompt(String resume, String jd, List<String> askedQuestions,
                                 String lastQuestion, String lastAnswer) {
    StringBuilder sb = new StringBuilder();
    sb.append("你是一位专业的技术面试官，需要根据候选人的简历和职位要求进行面试提问。\n\n");

    sb.append("## 候选人简历\n").append(resume).append("\n\n");
    sb.append("## 职位要求\n").append(jd).append("\n\n");

    // 已经问过的问题（提醒模型不要重复）
    if (askedQuestions != null && !askedQuestions.isEmpty()) {
        sb.append("## 已问过的问题（不要重复这些问题）\n");
        for (int i = 0; i < askedQuestions.size(); i++) {
            sb.append(i + 1).append(". ").append(askedQuestions.get(i)).append("\n");
        }
        sb.append("\n");
    }

    // 根据是否有上一题/上一答决定任务说明
    if (lastQuestion == null) {
        sb.append("## 任务\n");
        sb.append("请根据简历和职位要求，生成第一道技术面试题。");
        sb.append("要求：聚焦候选人的技术栈或项目经验，只输出问题本身，不要超过 100 字。\n");
    } else {
        sb.append("## 上一题及回答\n");
        sb.append("问题：").append(lastQuestion).append("\n");
        sb.append("回答：").append(lastAnswer).append("\n\n");

        sb.append("## 任务\n");
        sb.append("请基于候选人的上一条回答进行深入追问，或转向一个新的关键考点。");
        sb.append("只输出问题本身，不要超过 100 字，避免重复之前已经问过的问题。\n");
    }

    return new Prompt(sb.toString());
}
```

### 3.2 进阶：PromptTemplate (当前采用)
**特点**：提示词与代码逻辑解耦，支持占位符替换，易于维护。模板文件独立管理，修改提示词无需改代码。

#### 3.2.1 模板文件（`resources/newprompts/dynamicinterview.st`）
```st
你是一位专业的技术面试官，需要根据候选人的简历和职位要求进行面试提问。

## 候选人简历
{resume}

## 职位要求
{jd}

{asked_questions_section}

{task_section}
```

#### 3.2.2 代码实现
```java
@Autowired
private ResourceLoader resourceLoader;

private PromptTemplate dynamicInterviewTemplate;

@PostConstruct
public void init() {
    log.info("【动态面试】加载 Prompt 模板...");
    org.springframework.core.io.Resource resource = 
        resourceLoader.getResource("classpath:newprompts/dynamicinterview.st");
    this.dynamicInterviewTemplate = new PromptTemplate(resource);
}

// 构建 Prompt 时只需填充变量，不再手动拼接字符串
public void generateQuestionStream(...) {
    // ... 获取上下文数据 ...
    
    Map<String, Object> variables = Map.of(
        "resume", context.resume,
        "jd", context.jd,
        "asked_questions_section", askedQuestionsSection,
        "task_section", taskSection
    );
    
    // 模板自动替换占位符
    Prompt aiPrompt = dynamicInterviewTemplate.create(variables);
    
    // 使用流式调用
    qwenModel.stream(aiPrompt).subscribe(...);
}
```

#### 3.2.3 优势对比
| 对比项 | StringBuilder 拼接（旧） | PromptTemplate（新） |
|--------|------------------------|---------------------|
| **可维护性** | 提示词散落在代码中，修改需要重新编译 | 模板文件独立，可随时调整 |
| **可读性** | 大量 `append()` 调用，结构不清晰 | 模板文件一目了然，结构清晰 |
| **复用性** | 每次调用都要重新拼接 | 模板可被多处复用 |
| **版本控制** | 代码变更导致 diff 较大 | 模板变更独立，diff 更清晰 |
| **多语言支持** | 需要在代码中硬编码 | 可以为不同语言准备不同模板文件 |

---

## 4. 接口防护：RateLimiter 限流

为了防止 API 被刷或者超出大模型商家的 QPS 限制，我们在 `AiModelWrapperService` 中使用了限流器：

```java
// 每秒仅允许 2 次调用
private final RateLimiter rateLimiter = RateLimiter.create(2.0);

public String callAiModel(ChatModel model, Prompt prompt) {
    // 如果超过速率，线程会在此处阻塞等待令牌
    rateLimiter.acquire(); 
    return model.call(prompt).getResult().getOutput().getText();
}
```

