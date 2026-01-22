# AI 动态面试系统 - 项目笔记

## 目录
1. [项目概述](#1-项目概述)
2. [简历数据清洗（ETL）](#2-简历数据清洗etl)
3. [动态面试提问系统](#3-动态面试提问系统)
4. [阿里云语音识别（ASR）](#4-阿里云语音识别asr)
5. [前端实现详解](#5-前端实现详解)
6. [依赖管理与问题解决](#6-依赖管理与问题解决)

---

## 1. 项目概述

### 技术栈
- **后端**: Spring Boot 3.x + Spring AI + Redis
- **AI模型**: 阿里云 DashScope（通义千问 Qwen）
- **文档解析**: Apache Tika（Spring AI ETL）
- **语音识别**: 阿里云 DashScope Paraformer
- **前端**: uni-app（支持 H5 / APP / 小程序）
- **通信方式**: SSE（Server-Sent Events）流式传输

### 核心流程
```
用户上传简历 → ETL清洗 → 存储Redis → 动态面试 → 流式返回问题 → 语音回答 → ASR识别 → 下一题
```

---

## 2. 简历数据清洗（ETL）

### 2.1 什么是 ETL？
ETL = Extract（提取）+ Transform（转换）+ Load（加载）

| 阶段 | 作用 | 本项目实现 |
|------|------|-----------|
| Extract | 从各种格式文件提取原始文本 | TikaDocumentReader 解析 PDF/Word/TXT |
| Transform | 清洗、过滤、格式化数据 | 自定义规则清洗逻辑 |
| Load | 存储到目标系统 | Redis 缓存（24小时过期） |

### 2.2 为什么要清洗？

原始简历存在的问题：
1. **隐私信息泄露**：手机号、邮箱、身份证号
2. **冗余内容**：教育经历、获奖证书、兴趣爱好（对技术面试无用）
3. **格式混乱**：多余空行、特殊符号、中括号标签
4. **Token 浪费**：冗余内容消耗 AI 的 Token 配额，影响质量

### 2.3 清洗策略：白名单模式

**核心思想**：只保留有价值的内容，其余全部过滤

#### 保留的内容（白名单）
- ✅ 项目经历
- ✅ 工作经历  
- ✅ 实习经历
- ✅ 技术栈列表
- ✅ 工作描述（包含动词：负责、实现、开发...）

#### 过滤的内容（黑名单）
- ❌ 姓名、年龄、性别
- ❌ 手机号、邮箱、身份证
- ❌ 教育经历（学校名、入学年份）
- ❌ 个人评价、自我简介
- ❌ 获奖证书、荣誉
- ❌ 求职意向

### 2.4 核心代码实现

#### 使用的正则表达式
```java
// 敏感信息匹配
private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");

// 教育经历匹配（用于排除）
private static final Pattern EDUCATION_PATTERN = Pattern.compile(".*(大学|学院|学校|中学|高中|本科|硕士|博士|研究生|专科|大专).*");

// 格式清理
private static final Pattern BRACKET_LABEL_PATTERN = Pattern.compile("【[^】]{0,15}：?】");
private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[(邮箱|电话|姓名|年龄|性别|地址)\\]");
```

#### 清洗流程
```java
private String transformResume(String rawText) {
    // 1. 移除敏感信息
    String cleaned = PHONE_PATTERN.matcher(rawText).replaceAll("");
    cleaned = EMAIL_PATTERN.matcher(cleaned).replaceAll("");
    cleaned = ID_CARD_PATTERN.matcher(cleaned).replaceAll("");

    // 2. 规范化空白字符
    cleaned = MULTI_NEWLINE_PATTERN.matcher(cleaned).replaceAll("\n");
    cleaned = MULTI_SPACE_PATTERN.matcher(cleaned).replaceAll(" ");

    // 3. 按行处理，只保留有价值的内容
    String[] lines = cleaned.split("\n");
    StringBuilder result = new StringBuilder();
    boolean inValueSection = false;  // 状态机：是否在有价值的段落中
    
    for (String line : lines) {
        String trimLine = line.trim();
        
        // 检测段落标题
        if (isSectionHeader(trimLine)) {
            if (isValuableSection(trimLine)) {
                inValueSection = true;  // 进入有价值段落
                result.append("## ").append(cleanSectionTitle(trimLine)).append("\n");
            } else {
                inValueSection = false; // 进入无价值段落（不输出）
            }
            continue;
        }
        
        // 技术栈行（任何位置都保留）
        if (isTechStackLine(trimLine)) {
            result.append("技术栈: ").append(trimLine).append("\n");
            continue;
        }
        
        // 在有价值段落中，保留工作描述
        if (inValueSection && isValuableContent(trimLine)) {
            result.append(trimLine).append("\n");
        }
    }
    
    return finalResult;
}
```

#### 辅助判断方法

```java
// 判断是否为有价值的段落标题
private boolean isValuableSection(String line) {
    return line.matches(".*(项目|工作|实习|技术|技能|专业技能).*") && 
           !line.contains("意向") && !line.contains("期望");
}

// 判断是否为项目/工作标题行（包含时间和职位）
private boolean isWorkProjectTitle(String line) {
    // 排除教育相关
    if (EDUCATION_PATTERN.matcher(line).matches()) {
        return false;
    }
    // 必须包含时间 + (职位 或 公司名)
    boolean hasTime = line.matches(".*\\d{4}.*[年月.].*");
    boolean hasRole = line.matches(".*(工程师|开发|实习|经理|架构师|运维|测试).*");
    boolean hasCompany = line.matches(".*(公司|科技|集团|互联网|软件).*");
    return hasTime && (hasRole || hasCompany);
}

// 判断是否为有价值的内容
private boolean isValuableContent(String line) {
    // 数字编号内容（1. 2. 3.）
    if (line.matches("^\\d+[.、].*")) return true;
    
    // 包含工作动词的描述
    String workVerbs = ".*(负责|实现|开发|使用|采用|基于|完成|优化|设计|搭建|维护).*";
    if (line.length() > 15 && line.matches(workVerbs)) return true;
    
    return false;
}

// 判断是否为技术栈行（如：SpringBoot、MySQL、Redis）
private boolean isTechStackLine(String line) {
    if (line.contains("、") || line.contains(",")) {
        String[] techs = line.split("[、,，]");
        int techCount = 0;
        for (String tech : techs) {
            if (tech.trim().matches(".*[A-Za-z].*") && tech.length() < 30) {
                techCount++;
            }
        }
        return techCount >= 3;  // 至少3个技术词才算技术栈行
    }
    return false;
}
```

### 2.5 清洗效果对比

| 指标 | 清洗前 | 清洗后 |
|------|--------|--------|
| 字符数 | ~5000 | ~1500 |
| Token消耗 | ~2000 | ~600 |
| 包含隐私 | 有 | 无 |
| AI面试质量 | 一般（被冗余信息干扰） | 高（精准针对技术栈） |

---

## 3. 动态面试提问系统

### 3.1 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    动态面试流程                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐          │
│  │ 上传简历  │───→│ ETL清洗  │───→│ 存Redis  │          │
│  └──────────┘    └──────────┘    └──────────┘          │
│                                       │                 │
│                                       ▼                 │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐          │
│  │ SSE返回  │←───│ AI生成题 │←───│初始化上下文│          │
│  └──────────┘    └──────────┘    └──────────┘          │
│       │                               ▲                 │
│       ▼                               │                 │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐          │
│  │ 用户回答  │───→│ ASR识别  │───→│记录已问题│          │
│  └──────────┘    └──────────┘    └──────────┘          │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 PromptTemplate 模板化

**为什么用 PromptTemplate？**
- 将提示词与代码解耦
- 方便修改和调试
- 支持变量替换
- 便于版本管理

#### 模板文件：`dynamicinterview.st`
```text
你是一位专业的技术面试官，需要根据候选人的简历和职位要求进行面试提问。

## 核心规则（必须严格遵守）
1. **绝对禁止重复**：不能问已问过的问题，也不能问其变体
2. **已覆盖的主题不能再问**：如果已问过"ThreadLocal"，不能再问任何ThreadLocal相关
3. **每题必须考察不同技术点**
4. 可以对回答中提到的**新技术点**追问，但不能追问已问主题

## 候选人简历
{resume}

## 职位要求
{jd}

{asked_questions_section}

{task_section}

【最终检查】在输出前，对比你的问题与已问列表，确保没有任何主题重叠。
```

#### Java 中使用 PromptTemplate
```java
@PostConstruct
public void init() {
    Resource resource = resourceLoader.getResource("classpath:newprompts/dynamicinterview.st");
    this.dynamicInterviewTemplate = new PromptTemplate(resource);
}

// 使用时填充变量
Map<String, Object> variables = Map.of(
    "resume", context.resume,
    "jd", context.jd,
    "asked_questions_section", askedQuestionsSection,
    "task_section", taskSection
);
Prompt aiPrompt = dynamicInterviewTemplate.create(variables);
```

### 3.3 防止重复提问

**问题**：AI 容易问重复的问题（或变体）

**解决方案**：
1. Redis 记录已问问题列表
2. Prompt 中明确禁止重复
3. 将已问问题全部列出给 AI 参考

```java
// 构建已问问题部分
String askedQuestionsSection = "";
if (!askedQuestions.isEmpty()) {
    StringBuilder sb = new StringBuilder("## 已问过的问题及主题（禁止再问）\n");
    for (int i = 0; i < askedQuestions.size(); i++) {
        sb.append(i + 1).append(". ").append(askedQuestions.get(i)).append("\n");
    }
    sb.append("\n## 已覆盖的技术主题（禁止再问）\n");
    sb.append("从上述问题中提取的主题都不能再问，包括其变体和深入追问。\n");
    askedQuestionsSection = sb.toString();
}
```

### 3.4 SSE 流式传输

**什么是 SSE？**
- Server-Sent Events（服务器推送事件）
- 服务器单向推送数据到客户端
- 适合实时消息、流式输出场景
- 比 WebSocket 简单，但只支持单向

**后端实现**
```java
public void generateQuestionStream(String candidateId, ..., SseEmitter emitter) {
    // 流式调用 AI
    qwenModel.stream(aiPrompt).subscribe(
        response -> {
            String chunk = response.getResult().getOutput().getText();
            // 每收到一个片段就推送给前端
            emitter.send(SseEmitter.event().name("question")
                .data("{\"content\":\"" + chunk + "\",\"finished\":false}"));
        },
        error -> { /* 错误处理 */ },
        () -> {
            // 完成时发送结束标记
            emitter.send(SseEmitter.event().name("question")
                .data("{\"finished\":true}"));
            emitter.complete();
        }
    );
}
```

---

## 4. 阿里云语音识别（ASR）

### 4.1 为什么选择 DashScope Paraformer？

| 特性 | 百度 ASR | 阿里 DashScope |
|------|----------|----------------|
| API Key | 需要单独申请 | 与 Qwen 共用 |
| 计费 | 按次计费 | 统一计费 |
| 集成复杂度 | 需要 Token 管理 | 简单 |
| 准确率 | 高 | 高 |

### 4.2 核心实现

**关键点**：使用 `data:` URI 直接传输音频数据，无需上传到 OSS/MinIO

```java
public String recognize(byte[] audioData, String format) throws Exception {
    // 1. 将音频转为 Base64
    String base64Audio = Base64.getEncoder().encodeToString(audioData);
    String mimeType = getMimeType(format);  // audio/wav, audio/mpeg 等
    
    // 2. 构造 data URI（重点！避免了上传文件的步骤）
    String dataUri = "data:" + mimeType + ";base64," + base64Audio;
    
    // 3. 调用转写 API
    TranscriptionParam param = TranscriptionParam.builder()
        .apiKey(apiKey)
        .model("paraformer-v2")
        .fileUrls(Collections.singletonList(dataUri))  // 直接传 data URI
        .parameter("language_hints", new String[]{"zh", "en"})
        .build();
    
    Transcription transcription = new Transcription();
    
    // 4. 异步提交 + 等待结果
    TranscriptionResult result = transcription.asyncCall(param);
    result = transcription.wait(
        TranscriptionQueryParam.FromTranscriptionParam(param, result.getTaskId()));
    
    // 5. 解析结果
    return parseTranscriptionResult(result.getOutput());
}
```

### 4.3 结果解析

DashScope 返回结构比较复杂，需要多层解析：

```java
private String parseTranscriptionResult(JsonObject output) throws Exception {
    JsonArray results = output.getAsJsonArray("results");
    StringBuilder sb = new StringBuilder();
    
    for (int i = 0; i < results.size(); i++) {
        JsonObject taskResult = results.get(i).getAsJsonObject();
        // 结果存储在一个 URL 中，需要再次请求获取
        String transcriptionUrl = taskResult.get("transcription_url").getAsString();
        String jsonResult = fetchUrl(transcriptionUrl);
        
        // 从 JSON 中提取文本
        JsonObject root = JsonParser.parseString(jsonResult).getAsJsonObject();
        JsonArray transcripts = root.getAsJsonArray("transcripts");
        String text = transcripts.get(0).getAsJsonObject().get("text").getAsString();
        sb.append(text);
    }
    
    return sb.toString();
}
```

---

## 5. 前端实现详解

### 5.1 uni-app 跨平台适配

**问题**：不同平台对 SSE 的支持不同
- H5：原生支持 EventSource
- APP（安卓/iOS）：不支持 EventSource，需要用 XMLHttpRequest 或 uni.request

**解决方案**：条件编译

```javascript
// #ifdef H5
// H5 平台使用原生 EventSource
var eventSource = new EventSource(url);
eventSource.addEventListener('question', function(event) {
    var data = JSON.parse(event.data);
    me.currentQuestion += data.content;
});
// #endif

// #ifdef APP-PLUS
// APP 平台使用 XMLHttpRequest
var xhr = new XMLHttpRequest();
xhr.open('GET', url, true);
xhr.onreadystatechange = function() {
    if (xhr.readyState === 3 || xhr.readyState === 4) {
        me.parseSSEChunk(xhr.responseText);
    }
};
xhr.send();
// #endif
```

### 5.2 SSE 数据解析

SSE 数据格式：
```
event: question
data: {"content":"你好","finished":false}

event: question
data: {"content":"请问","finished":false}

event: question
data: {"finished":true}
```

解析代码：
```javascript
parseSSEChunk(chunk) {
    var lines = chunk.split('\n');
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (line.startsWith('data:')) {
            var dataStr = line.substring(5).trim();
            var jsonData = JSON.parse(dataStr);
            
            if (jsonData.content) {
                me.currentQuestion += jsonData.content;
            }
            if (jsonData.finished) {
                me.isLoadingQuestion = false;
                me.isRecordAudio = 1;  // 切换到"可以回答"状态
            }
        }
    }
}
```

### 5.3 题号计数修复

**问题**：题号一开始就显示"第2题"

**原因**：在获取第一题完成时错误递增了索引

**修复**：
```javascript
// 错误做法：在问题获取完成时递增
simulateStreamDisplay(fullText, isEnd) {
    // ...
    me.currentQuestionIndex++;  // ❌ 第一题完成时也会递增
}

// 正确做法：在"获取下一题"前递增
if (self.currentQuestionIndex < self.maxQuestions - 1) {
    self.currentQuestionIndex++;  // ✅ 只在获取下一题前递增
    self.fetchNextQuestion();
}
```

**显示逻辑**：
```html
<view class="header-info">第{{currentQuestionIndex+1}}题 / 共{{maxQuestions}}题</view>
```

索引从 0 开始，显示时 +1：
- 索引 0 → 显示"第1题"
- 索引 1 → 显示"第2题"

### 5.4 录音与上传

```javascript
// 初始化录音器
initRecorder() {
    this.recorderManager.onStop(function (res) {
        // 录音完成，上传到服务器
        uni.uploadFile({
            url: serverUrl + "/speech/uploadVoice",
            name: "file",
            filePath: res.tempFilePath,
            success(result) {
                var answerContent = JSON.parse(result.data).data;
                // 保存回答，请求下一题
                self.lastAnswer = answerContent;
                self.fetchNextQuestion();
            }
        });
    });
}

// 开始录音
startRecord() {
    this.recorderManager.start({
        duration: 600000,   // 最长10分钟
        sampleRate: 16000,  // 采样率
        numberOfChannels: 1 // 单声道
    });
}
```

---

## 6. 依赖管理与问题解决

### 6.1 Tika + PDFBox 版本冲突

**错误**：
```
java.lang.NoSuchMethodError: 'void org.apache.tika.parser.pdf.PDF2XHTML.setIgnoreContentStreamSpaceGlyphs(boolean)'
```

**原因**：Tika 3.1.0 需要 PDFBox 3.0.3+，但项目中有其他依赖引入了旧版本

**解决**：让 `spring-ai-tika-document-reader` 自动管理依赖，删除手动声明的 pdfbox

```xml
<!-- 父 pom.xml -->
<dependencyManagement>
    <dependencies>
        <!-- 不要手动指定 pdfbox 版本 -->
        <dependency>
            <groupId>commons-io</groupId>
            <artifactId>commons-io</artifactId>
            <version>2.15.1</version>  <!-- Tika 3.1 需要 2.15+ -->
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 6.2 jsonschema-generator 冲突

**错误**：
```
java.lang.NoClassDefFoundError: com/github/victools/jsonschema/generator/AnnotationHelper
```

**解决**：
```xml
<dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-generator</artifactId>
    <version>4.37.0</version>  <!-- 与 jsonschema-module-jackson 版本一致 -->
</dependency>
```

### 6.3 DashScope SDK 添加

```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>dashscope-sdk-java</artifactId>
    <version>2.19.0</version>
</dependency>
```

### 6.4 常用命令

```bash
# 查看依赖树
mvn dependency:tree -pl service

# 强制更新依赖
mvn dependency:resolve -U -pl service -am

# 清理并重新构建
mvn clean install -DskipTests
```

---

## 附录：关键文件清单

| 文件 | 作用 |
|------|------|
| `ResumeService.java` | 简历 ETL 清洗逻辑 |
| `DynamicInterviewService.java` | 动态面试核心逻辑 |
| `AliyunSpeechService.java` | 阿里云 ASR 语音识别 |
| `dynamicinterview.st` | 面试提问 Prompt 模板 |
| `dynamic-interview-chat.vue` | 面试聊天页面 |
| `dynamic-interview.vue` | 简历上传页面 |
| `me.vue` | 面试完成页面 |

---

*文档版本：v1.0*  
*更新日期：2024-12*

