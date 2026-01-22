# Spring AI ETL 与 DocumentTransformer 综合笔记

本笔记整合了项目中关于 Spring AI ETL 流程和 DocumentTransformer 的所有相关内容，包括架构演进、实现细节和使用示例。

---

## 一、Spring AI ETL 架构概述

### 1.1 ETL 流程

Spring AI 采用标准的 ETL（Extract-Transform-Load）模式处理文档：

```
Extract (提取) → Transform (转换) → Load (加载)
     ↓                ↓                ↓
TikaDocumentReader  DocumentTransformer  Redis/VectorStore
```

### 1.2 核心组件

| 组件 | 作用 | 实现类/接口 |
|------|------|------------|
| **DocumentReader** | 文档提取 | `TikaDocumentReader` |
| **Document** | 文档对象 | `org.springframework.ai.document.Document` |
| **DocumentTransformer** | 文档转换 | `org.springframework.ai.document.DocumentTransformer` (接口) |
| **VectorStore** | 向量存储 | `RedisVectorStore` |

---

## 二、简历处理 ETL 演进历程

### 2.1 写法 A：AI 增强型清洗（旧版，已废弃）

**特点**：使用大模型识别字段，逻辑强大但速度慢（3-10秒）。

```java
// 使用 AI 进行清洗
public String cleanResumeWithAI(String rawText) {
    String prompt = "请从以下文本中移除隐私信息，并整理出工作经历、项目经历和技术栈：\n" + rawText;
    return chatModel.call(prompt); 
}
```

**缺点**：
- 响应慢（3-10秒）
- 成本高（每次调用大模型）
- 不稳定（依赖AI输出质量）

### 2.2 写法 B：手动正则表达式清洗（旧版，已废弃）

**特点**：基于正则表达式和关键词过滤，响应极快（<100ms）。

```java
// Extract: 传统方式读取文件
InputStream is = file.getInputStream();
// Transform: 手动正则过滤
String cleaned = rawText.replaceAll("1[3-9]\\d{9}", "[手机号已隐藏]");
// Load: 存入 Redis
redisTemplate.opsForValue().set(key, cleaned);
```

**缺点**：
- 代码耦合度高
- 难以扩展和维护
- 不符合 Spring AI 架构规范

### 2.3 写法 C：标准 Spring AI ETL（当前采用）✅

**特点**：使用 Spring AI 提供的 `DocumentReader` 和 `DocumentTransformer` 接口，架构优雅，自动支持多种格式。

**完整流程**：
```java
// 1. Extract: 使用 Spring AI TikaDocumentReader 自动解析 PDF/Word/TXT
TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(file.getInputStream()));
List<Document> documents = reader.get();

String rawText = documents.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n"));

// 2. Transform: 使用 Spring AI DocumentTransformer 链式转换器进行文本清洗
List<DocumentTransformer> transformers = textProcessingService.createResumeCleaningTransformers();
String cleanedText = textProcessingService.cleanText(rawText, transformers);

// 3. 业务逻辑处理：白名单过滤（保留有价值的内容）
cleanedText = filterValuableContent(cleanedText);

// 4. Load: 存储到 Redis
stringRedisTemplate.opsForValue().set(redisKey, cleanedText, RESUME_EXPIRE_HOURS, TimeUnit.HOURS);
```

**优势**：
- ✅ 符合 Spring AI 架构规范
- ✅ 可扩展性强（链式转换器）
- ✅ 代码清晰易维护
- ✅ 自动支持多种文档格式

---

## 三、DocumentTransformer 详解

### 3.1 接口定义

`DocumentTransformer` 是 Spring AI 提供的函数式接口：

```java
@FunctionalInterface
public interface DocumentTransformer {
    List<Document> apply(List<Document> documents);
}
```

### 3.2 Spring AI 提供的现成实现

Spring AI 提供了一些现成的 `DocumentTransformer` 实现类，可以直接使用：

| 实现类 | 功能 | 使用场景 |
|--------|------|----------|
| **TextSplitter** | 文本分割 | 将长文本分割成更小的片段 |
| **TokenTextSplitter** | 基于Token的文本分割 | 考虑语义边界（如句子结尾）创建有意义的文本段落 |
| **ContentFormatTransformer** | 内容格式化 | 处理文档内容的格式化 |
| **KeywordMetadataEnricher** | 关键词元数据增强 | 使用生成式方法提取关键字并增强元数据 |
| **SummaryMetadataEnricher** | 摘要元数据增强 | 使用生成式方法提取摘要并增强元数据 |

### 3.3 自定义实现

**注意**：对于特定的业务需求（如隐私信息移除、冗余标签移除、空白字符规范化等），Spring AI 没有提供现成的实现，需要通过 lambda 表达式或实现类自定义实现。

### 3.3 项目中的实现

#### 3.3.1 TextProcessingService 服务

位置：`service/src/main/java/com/axle/service/RAG/TextProcessingService.java`

**核心方法**：

```java
/**
 * 使用 Spring AI DocumentTransformer 进行文本清洗
 * 可以链式应用多个转换器
 */
public String cleanText(String content, List<DocumentTransformer> transformers) {
    if (content == null || content.isEmpty()) {
        return content;
    }
    
    Document document = new Document(content);
    
    // 链式应用所有转换器
    for (DocumentTransformer transformer : transformers) {
        List<Document> transformed = transformer.apply(List.of(document));
        if (transformed != null && !transformed.isEmpty()) {
            document = transformed.get(0);
        }
    }
    
    return document.getText();
}
```

#### 3.3.2 自定义转换器（业务特定需求）

由于 Spring AI 提供的现成实现无法满足业务特定需求（如隐私信息移除、冗余标签移除等），我们需要自定义实现。

**1. 隐私信息移除转换器**

```java
public DocumentTransformer createPrivacyRemovalTransformer() {
    return documents -> documents.stream()
            .map(doc -> {
                String text = doc.getText();
                if (text == null) {
                    text = "";
                }
                // 移除手机号
                text = text.replaceAll("1[3-9]\\d{9}", "");
                // 移除邮箱
                text = text.replaceAll("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", "");
                // 移除身份证号
                text = text.replaceAll("\\d{17}[\\dXx]", "");
                
                Document cleaned = new Document(text);
                cleaned.getMetadata().putAll(doc.getMetadata());
                return cleaned;
            })
            .collect(Collectors.toList());
}
```

**2. 冗余标签移除转换器**

```java
public DocumentTransformer createRedundantLabelRemovalTransformer() {
    return documents -> documents.stream()
            .map(doc -> {
                String text = doc.getText();
                if (text == null) {
                    text = "";
                }
                // 移除【标签】格式
                text = text.replaceAll("【[^】]{0,15}：?】", "");
                // 移除[占位符]格式（邮箱、电话、姓名等）
                text = text.replaceAll("\\[(邮箱|电话|姓名|年龄|性别|地址)\\]", "");
                
                Document cleaned = new Document(text);
                cleaned.getMetadata().putAll(doc.getMetadata());
                return cleaned;
            })
            .collect(Collectors.toList());
}
```

**3. 空白字符规范化转换器**

```java
public DocumentTransformer createWhitespaceNormalizationTransformer() {
    return documents -> documents.stream()
            .map(doc -> {
                String text = doc.getText();
                if (text == null) {
                    text = "";
                }
                // 规范化换行符
                text = text.replaceAll("\\n{3,}", "\n\n");
                // 规范化空格
                text = text.replaceAll("[ \\t]{2,}", " ");
                // 规范化斜杠空格
                text = text.replaceAll(" / ", " ").replaceAll(" /", " ").replaceAll("/ ", " ");
                // 移除行首行尾空白
                text = text.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .collect(Collectors.joining("\n"));
                
                Document normalized = new Document(text);
                normalized.getMetadata().putAll(doc.getMetadata());
                return normalized;
            })
            .collect(Collectors.toList());
}
```

#### 3.3.3 转换器链

```java
/**
 * 创建简历专用清洗转换器链
 * 包含隐私移除、冗余标签移除、空白规范化
 */
public List<DocumentTransformer> createResumeCleaningTransformers() {
    return List.of(
            createPrivacyRemovalTransformer(),        // 第一步：移除隐私信息
            createRedundantLabelRemovalTransformer(), // 第二步：移除冗余标签
            createWhitespaceNormalizationTransformer() // 第三步：规范化空白字符
    );
}
```

**执行顺序**：
1. 隐私信息移除 → 2. 冗余标签移除 → 3. 空白字符规范化

---

## 四、ResumeService 中的使用

### 4.1 完整实现

位置：`service/src/main/java/com/axle/service/ResumeService.java`

```java
@Service
@Slf4j
public class ResumeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TextProcessingService textProcessingService;

    /**
     * 上传并处理简历（标准的 Spring AI ETL 流程）
     */
    public String uploadAndProcessResume(String candidateId, MultipartFile file) throws IOException {
        log.info("【Spring AI ETL】开始处理简历，候选人ID: {}", candidateId);
        long startTime = System.currentTimeMillis();

        // 1. Extract: 使用 Spring AI TikaDocumentReader 自动解析 PDF/Word/TXT
        TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(file.getInputStream()));
        List<Document> documents = reader.get();
        
        String rawText = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
        
        // 2. Transform: 使用 Spring AI DocumentTransformer 进行文本清洗
        List<DocumentTransformer> transformers = textProcessingService.createResumeCleaningTransformers();
        String cleanedText = textProcessingService.cleanText(rawText, transformers);
        
        // 3. 业务逻辑处理：白名单过滤（保留有价值的内容）
        cleanedText = filterValuableContent(cleanedText);

        // 4. Load: 存储到 Redis
        String redisKey = REDIS_RESUME_PREFIX + candidateId;
        stringRedisTemplate.opsForValue().set(redisKey, cleanedText, RESUME_EXPIRE_HOURS, TimeUnit.HOURS);

        long duration = System.currentTimeMillis() - startTime;
        log.info("【Spring AI ETL】完成！耗时: {}ms", duration);

        return "简历上传成功";
    }
}
```

### 4.2 备用代码（正则表达式方式）

为了保留历史代码，项目中保留了正则表达式的备用实现：

```java
// ========== 以下为备用代码：正则表达式清洗方式（已由 Spring AI DocumentTransformer 替代）==========
@SuppressWarnings("unused")
private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
@SuppressWarnings("unused")
private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", Pattern.CASE_INSENSITIVE);
// ... 其他 Pattern 定义

/**
 * 备用方法：使用正则表达式进行文本清洗（已废弃，由 Spring AI DocumentTransformer 替代）
 */
@SuppressWarnings("unused")
private String transformResumeWithRegex(String rawText) {
    // ... 正则表达式清洗逻辑
}
```

---

## 五、其他文本处理功能

### 5.1 智能文本截断

```java
/**
 * 使用 Spring AI Document 进行智能文本截断
 * 尝试在合适的边界（句号、换行符、空格）截断，避免截断单词
 */
public String truncateText(String content, int maxLength) {
    if (content == null || content.isEmpty() || content.length() <= maxLength) {
        return content;
    }
    
    String truncated = content.substring(0, maxLength);
    
    // 尝试在句号、换行符等位置截断，避免截断单词
    int lastPeriod = truncated.lastIndexOf('。');
    int lastNewline = truncated.lastIndexOf('\n');
    int lastSpace = truncated.lastIndexOf(' ');
    
    int cutPoint = Math.max(Math.max(lastPeriod, lastNewline), lastSpace);
    if (cutPoint > maxLength * 0.8) { // 如果找到的截断点在80%之后，使用该点
        truncated = truncated.substring(0, cutPoint + 1);
    }
    
    return truncated + "...";
}
```

### 5.2 智能段落截断

```java
/**
 * 智能截断文本（基于语义，而非简单字符截断）
 * 使用 Spring AI Document 在段落边界截断
 */
public String smartTruncate(String content, int maxLength) {
    if (content == null || content.isEmpty() || content.length() <= maxLength) {
        return content;
    }
    
    // 尝试在段落边界截断
    String[] paragraphs = content.split("\n\n");
    StringBuilder result = new StringBuilder();
    
    for (String paragraph : paragraphs) {
        if (result.length() + paragraph.length() + 2 <= maxLength) {
            if (result.length() > 0) {
                result.append("\n\n");
            }
            result.append(paragraph);
        } else {
            break;
        }
    }
    
    // 如果结果为空或太短，使用简单截断
    if (result.length() < maxLength * 0.5) {
        return truncateText(content, maxLength);
    }
    
    return result.toString() + "...";
}
```

---

## 六、优势总结

### 6.1 架构优势

1. **符合 Spring AI 设计模式**：使用标准的 ETL 流程和接口
2. **可扩展性强**：可以轻松添加新的 `DocumentTransformer`
3. **代码清晰**：职责分离，易于维护
4. **类型安全**：使用 Spring AI 的类型系统

### 6.2 功能优势

1. **链式转换**：多个转换器可以组合使用
2. **元数据保留**：转换过程中保留文档元数据
3. **智能截断**：在语义边界截断，避免破坏文本完整性
4. **统一接口**：所有文本处理都通过统一的服务接口

### 6.3 性能优势

1. **响应快速**：规则清洗响应时间 <100ms（相比 AI 清洗的 3-10秒）
2. **成本低**：不需要调用大模型
3. **稳定性高**：不依赖 AI 输出质量

---

## 七、使用示例

### 7.1 简历清洗

```java
// 创建转换器链
List<DocumentTransformer> transformers = textProcessingService.createResumeCleaningTransformers();

// 应用转换器
String cleaned = textProcessingService.cleanText(rawText, transformers);
```

### 7.2 文本截断

```java
// 简单截断
String truncated = textProcessingService.truncateText(content, 3000);

// 智能截断（基于段落）
String smart = textProcessingService.smartTruncate(content, 5000);
```

### 7.3 自定义转换器

```java
// 创建自定义转换器
DocumentTransformer customTransformer = documents -> documents.stream()
        .map(doc -> {
            String text = doc.getText();
            // 自定义处理逻辑
            text = text.toUpperCase(); // 示例：转大写
            
            Document transformed = new Document(text);
            transformed.getMetadata().putAll(doc.getMetadata());
            return transformed;
        })
        .collect(Collectors.toList());

// 使用自定义转换器
List<DocumentTransformer> transformers = List.of(
        textProcessingService.createPrivacyRemovalTransformer(),
        customTransformer,  // 添加自定义转换器
        textProcessingService.createWhitespaceNormalizationTransformer()
);
String cleaned = textProcessingService.cleanText(content, transformers);
```

---

## 八、相关文件

### 8.1 核心实现文件

- `service/src/main/java/com/axle/service/RAG/TextProcessingService.java` - 文本处理服务
- `service/src/main/java/com/axle/service/ResumeService.java` - 简历处理服务

### 8.2 依赖配置

在 `service/pom.xml` 中需要包含：

```xml
<!-- Spring AI Tika Document Reader (ETL) - 包含PDF/Word解析 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>
```

### 8.3 相关笔记

- `etl_and_sync_methods_notes.md` - ETL 和同步方法笔记
- `docs/代码优化和问题修复总结.md` - 代码优化记录

---

## 九、Spring AI 现成实现 vs 自定义实现

### 9.1 使用 Spring AI 现成实现

对于通用场景，可以直接使用 Spring AI 提供的实现：

```java
// 使用 TextSplitter 进行文本分割
TextSplitter textSplitter = new TokenTextSplitter();
List<Document> splitDocuments = textSplitter.apply(documents);

// 使用 KeywordMetadataEnricher 提取关键词
KeywordMetadataEnricher keywordEnricher = new KeywordMetadataEnricher(chatModel);
List<Document> enrichedDocuments = keywordEnricher.apply(documents);
```

### 9.2 自定义实现（业务特定需求）

对于业务特定需求，需要自定义实现：

```java
// 自定义隐私信息移除转换器
public DocumentTransformer createPrivacyRemovalTransformer() {
    return documents -> documents.stream()
            .map(doc -> {
                String text = doc.getText();
                // 业务特定的清洗逻辑
                text = text.replaceAll("1[3-9]\\d{9}", ""); // 移除手机号
                // ...
                return new Document(text);
            })
            .collect(Collectors.toList());
}
```

### 9.3 混合使用

可以混合使用 Spring AI 现成实现和自定义实现：

```java
List<DocumentTransformer> transformers = List.of(
        // 使用 Spring AI 现成实现
        new TokenTextSplitter(),
        // 使用自定义实现
        createPrivacyRemovalTransformer(),
        createWhitespaceNormalizationTransformer()
);
```

## 十、后续优化方向

1. **使用 Spring AI 现成实现**：对于文本分割等通用场景，可以使用 `TextSplitter` 或 `TokenTextSplitter`
2. **Token 级别截断**：可以使用 Spring AI 的 `TokenCountEstimator` 进行基于 Token 的截断
3. **元数据增强**：可以使用 `KeywordMetadataEnricher` 或 `SummaryMetadataEnricher` 增强文档元数据
4. **自定义转换器**：为特定业务场景创建更多自定义 `DocumentTransformer`
5. **性能优化**：对于大量文本，可以考虑并行处理
6. **缓存机制**：对清洗结果进行缓存，避免重复处理

---

## 十、总结

本项目成功将简历处理从硬编码的正则表达式方式迁移到基于 Spring AI 的标准 ETL 架构：

- ✅ **Extract 阶段**：使用 `TikaDocumentReader` 自动解析多种文档格式
- ✅ **Transform 阶段**：使用 `DocumentTransformer` 链式转换器进行文本清洗
- ✅ **Load 阶段**：将处理后的文本存储到 Redis

这种架构不仅符合 Spring AI 的设计规范，还提供了更好的可扩展性和可维护性，为后续功能扩展打下了良好基础。

---

**最后更新**：2024年（根据实际日期更新）  
**维护者**：项目开发团队

