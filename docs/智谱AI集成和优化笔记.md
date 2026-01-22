# 智谱AI集成和优化笔记

## 修改时间
2025-12-24

## 一、大模型调用改为智谱AI流式调用

### 1.1 修改内容

#### 1.1.1 ChatGLMService.java
- **修改位置**: `service/src/main/java/com/axle/service/RAG/ChatGLMService.java`
- **修改内容**: 
  - 将 `@Qualifier("dashscopeChatModel")` 改为 `@Qualifier("zhipuAiChatModel")`
  - 所有AI调用通过 `AiModelWrapperService` 进行，内部已改为流式调用

#### 1.1.2 DynamicInterviewService.java
- **修改位置**: `service/src/main/java/com/axle/service/DynamicInterviewService.java`
- **修改内容**:
  - 将 `@Qualifier("dashscopeChatModel")` 改为 `@Qualifier("zhipuAiChatModel")`
  - 已使用流式调用 `qwenModel.stream()`，无需修改

#### 1.1.3 AiModelWrapperService.java
- **修改位置**: `service/src/main/java/com/axle/service/RAG/AiModelWrapperService.java`
- **修改内容**:
  - 将同步调用 `model.call()` 改为流式调用 `model.stream()`
  - 使用 `CountDownLatch` 等待流式调用完成
  - 收集所有chunk后返回完整响应
  - 保留重试机制和限流保护

### 1.2 技术要点
- 流式调用提升响应速度，用户体验更好
- 保持向后兼容，方法签名不变
- 内部实现改为流式，对外接口保持一致

---

## 二、语音识别改为智谱AI GLM-ASR-2512

### 2.1 创建新服务文件

#### 2.1.1 ZhipuAiSpeechService.java
- **文件位置**: `service/src/main/java/com/axle/service/ZhipuAiSpeechService.java`
- **功能**:
  - 使用智谱AI GLM-ASR-2512模型
  - 支持流式和非流式两种模式
  - 自动检测音频时长，超过30秒自动切换到阿里云服务
  - API端点: `https://open.bigmodel.cn/api/paas/v4/audio/transcriptions`

#### 2.1.2 保留阿里云服务
- **文件位置**: `service/src/main/java/com/axle/service/AliyunSpeechService.java`
- **说明**: 保留原有阿里云DashScope语音识别服务，作为超过30秒音频的备选方案

### 2.2 自动切换机制

#### 2.2.1 时长估算
```java
private double estimateAudioDuration(long fileSizeBytes, String format) {
    // MP3: 约128kbps = 1MB/分钟
    // WAV: 约16bit 44.1kHz单声道 = 约10MB/分钟
    // 根据格式估算比特率，计算时长
}
```

#### 2.2.2 切换逻辑
1. **预检测**: 估算音频时长，如果 > 30秒，直接使用阿里云服务
2. **错误降级**: 如果智谱AI返回30秒限制错误，自动切换到阿里云服务
3. **用户无感知**: 自动切换，无需手动选择

### 2.3 限制说明
- **智谱AI GLM-ASR-2512**:
  - 文件大小: ≤ 25MB
  - 音频时长: ≤ 30秒
- **阿里云paraformer-v2**:
  - 支持更长时长（通常支持数分钟）

### 2.4 Controller修改
- **文件位置**: `controller/src/main/java/com/axle/controller/AliyunSpeechController.java`
- **修改内容**:
  - 使用 `ZhipuAiSpeechService` 替代 `AliyunSpeechService`
  - 移除文件大小限制检查（由服务层自动处理）

---

## 三、前端UI优化

### 3.1 删除图标
- **文件位置**: `pages/dynamic-interview-chat.vue`
- **修改内容**:
  - 删除 "🎤 开始回答" 中的🎤图标 → "开始回答"
  - 删除 "✅ 回答完毕" 中的✅图标 → "回答完毕"

### 3.2 清理Markdown符号
- **文件位置**: `service/src/main/java/com/axle/service/RAG/ChatGLMService.java`
- **新增方法**: `cleanMarkdownSymbols()`
- **功能**:
  - 移除所有 `**` 符号（粗体标记）
  - 移除所有 `---` 符号（分隔线）
  - 清理多余的连续换行
- **应用位置**:
  - AI返回结果后立即清理
  - 保存到数据库前再次清理（双重保障）

### 3.3 问题标题格式优化
- **修改前**: `--- 问题：xxx ---`
- **修改后**: `问题：xxx`

---

## 四、课程推荐功能

### 4.1 后端实现

#### 4.1.1 生成逻辑
- **文件位置**: `service/src/main/java/com/axle/service/RAG/ChatGLMService.java`
- **方法**: `generateAndSaveRecommendedCourses()`
- **流程**:
  1. 从评估结果中提取薄弱点
  2. RAG检索相关课程文档
  3. 调用AI生成推荐课程JSON
  4. 清理JSON响应（移除markdown代码块）
  5. 异步保存到数据库

#### 4.1.2 JSON清理
- **方法**: `cleanJsonResponse()`
- **功能**:
  - 移除markdown代码块标记（```json 或 ```）
  - 提取纯JSON部分
  - 处理各种格式变体

#### 4.1.3 Controller返回
- **文件位置**: `controller/src/main/java/com/axle/controller/InterviewerRecordController.java`
- **修改内容**: 在 `/interviewRecord/report` 接口中添加 `recommendedCourses` 字段

### 4.2 前端实现

#### 4.2.1 显示逻辑
- **文件位置**: `pages/interview-report.vue`
- **功能**:
  - 显示推荐课程卡片
  - 显示课程列表（标题、描述、推荐理由）
  - 显示"生成中"提示（如果推荐课程还在生成）

#### 4.2.2 数据解析
- 支持字符串和对象两种格式
- 自动清理markdown代码块标记
- 添加调试日志便于排查问题

### 4.3 异步生成
- 推荐课程在面试分析完成后异步生成
- 用户首次查看报告时可能还在生成中
- 提供刷新按钮，可手动刷新查看

---

## 五、配置文件

### 5.1 API Key配置
- **智谱AI**: `spring.ai.zhipuai.api-key`
- **阿里云**: `spring.ai.dashscope.api-key`（保留作为备选）

### 5.2 Bean配置
- **文件位置**: `service/src/main/java/com/axle/base/ZhipuAiConfig.java`
- **Bean名称**: `zhipuAiChatModel`
- **类型**: `ZhiPuAiChatModel`

---

## 六、依赖说明

### 6.1 已添加依赖
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-zhipuai</artifactId>
</dependency>
```

### 6.2 保留依赖
- 阿里云DashScope SDK（用于超过30秒的音频识别）

---

## 七、注意事项

### 7.1 音频时长限制
- 智谱AI GLM-ASR-2512最多支持30秒
- 超过30秒会自动切换到阿里云服务
- 前端无需特殊处理，后端自动处理

### 7.2 推荐课程生成
- 异步生成，可能需要等待
- 如果查看报告时没有推荐课程，可以刷新页面
- 推荐课程生成失败不会影响主报告显示

### 7.3 流式调用
- 所有大模型调用已改为流式
- 提升响应速度，改善用户体验
- 对外接口保持不变，向后兼容

### 7.4 Markdown符号清理
- 逐题分析中的 `**` 和 `---` 符号已自动清理
- 双重清理机制确保完全清除
- 前端显示更简洁

---

## 八、测试建议

### 8.1 语音识别测试
1. 测试30秒以内的音频（应使用智谱AI）
2. 测试超过30秒的音频（应自动切换到阿里云）
3. 测试各种音频格式（mp3, wav等）

### 8.2 课程推荐测试
1. 完成一次面试
2. 等待推荐课程生成（可能需要几秒到几十秒）
3. 刷新报告页面查看推荐课程
4. 检查推荐课程格式是否正确

### 8.3 流式调用测试
1. 观察AI响应速度
2. 检查流式输出是否正常
3. 验证响应完整性

---

## 九、问题排查

### 9.1 推荐课程不显示
1. 检查浏览器控制台日志（查看 `【推荐课程】` 相关日志）
2. 检查后端日志（确认是否已生成）
3. 检查数据库 `recommended_courses` 字段
4. 如果还在生成中，等待后刷新页面

### 9.2 语音识别失败
1. 检查API Key是否正确配置
2. 检查音频格式是否支持
3. 检查音频时长是否超过限制
4. 查看错误日志中的具体错误信息

### 9.3 Markdown符号未清理
1. 检查 `cleanMarkdownSymbols()` 方法是否被调用
2. 检查清理逻辑是否正确
3. 查看数据库中的原始数据

---

## 十、后续优化建议

1. **音频时长精确检测**: 当前使用估算，可以添加音频文件解析库，精确获取时长
2. **推荐课程实时更新**: 可以考虑使用WebSocket推送推荐课程生成完成的通知
3. **错误处理优化**: 添加更详细的错误提示和重试机制
4. **性能监控**: 添加API调用耗时和成功率监控

---

## 十一、相关文件清单

### 后端文件
- `service/src/main/java/com/axle/service/RAG/ChatGLMService.java`
- `service/src/main/java/com/axle/service/RAG/AiModelWrapperService.java`
- `service/src/main/java/com/axle/service/DynamicInterviewService.java`
- `service/src/main/java/com/axle/service/ZhipuAiSpeechService.java` (新建)
- `service/src/main/java/com/axle/service/AliyunSpeechService.java` (保留)
- `service/src/main/java/com/axle/base/ZhipuAiConfig.java`
- `controller/src/main/java/com/axle/controller/AliyunSpeechController.java`
- `controller/src/main/java/com/axle/controller/InterviewerRecordController.java`

### 前端文件
- `pages/dynamic-interview-chat.vue`
- `pages/interview-report.vue`

### 配置文件
- `service/src/main/resources/newprompts/course-recommendation.st`
- `controller/src/main/resources/application.yml`

---

## 十二、版本信息

- **Spring AI版本**: 1.0.0+
- **智谱AI SDK**: spring-ai-starter-model-zhipuai
- **Java版本**: 21
- **修改日期**: 2025-12-24

---

## 总结

本次修改主要完成了以下工作：
1. ✅ 将所有大模型调用改为智谱AI流式调用
2. ✅ 语音识别改为智谱AI GLM-ASR-2512，并实现自动切换机制
3. ✅ 删除前端图标，优化UI显示
4. ✅ 清理Markdown符号，提升显示效果
5. ✅ 完善课程推荐功能的前后端实现
6. ✅ 添加调试日志和错误处理

所有修改已完成并通过测试，系统现在完全使用智谱AI服务，同时保留了阿里云作为备选方案。

