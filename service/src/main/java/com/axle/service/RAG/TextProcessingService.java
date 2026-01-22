package com.axle.service.RAG;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI 的文本处理服务
 * 使用 Spring AI 的 DocumentTransformer 进行文本清洗和处理
 * 替代硬编码的文本处理逻辑
 */
@Service
@Slf4j
public class TextProcessingService {

    /**
     * 使用 Spring AI Document 进行智能文本截断
     * 替代硬编码的 truncateIfNeeded 方法
     * 尝试在合适的边界（句号、换行符、空格）截断，避免截断单词
     * 
     * @param content 原始文本
     * @param maxLength 最大长度（字符数）
     * @return 截断后的文本
     */
    public String truncateText(String content, int maxLength) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        if (content.length() <= maxLength) {
            return content;
        }
        
        // 使用 Spring AI Document 进行文本处理
        String text = content;
        
        // 截断文本，保留前 maxLength 个字符
        String truncated = text.substring(0, maxLength);
        
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

    /**
     * 使用 Spring AI DocumentTransformer 进行文本清洗
     * 可以链式应用多个转换器
     * 
     * @param content 原始文本
     * @param transformers 转换器列表
     * @return 清洗后的文本
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

    /**
     * 创建隐私信息移除转换器
     * 使用 Spring AI 的 DocumentTransformer 接口
     */
    public DocumentTransformer createPrivacyRemovalTransformer() {
        return documents -> documents.stream()
                .map(doc -> {
                    String text = doc.getText();
                    if (text == null) {
                        text = "";
                    }
                    // 隐私脱敏：直接删除手机号、邮箱、身份证号
                    // 删除手机号
                    text = text.replaceAll("1[3-9]\\d{9}", "");
                    // 删除邮箱
                    text = text.replaceAll("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", "");
                    // 删除身份证号
                    text = text.replaceAll("\\d{17}[\\dXx]", "");
                    
                    Document cleaned = new Document(text);
                    cleaned.getMetadata().putAll(doc.getMetadata());
                    return cleaned;
                })
                .collect(Collectors.toList());
    }

    /**
     * 创建空白字符规范化转换器
     */
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

    /**
     * 创建冗余标签移除转换器
     * 移除简历中的冗余标签和占位符
     */
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

    /**
     * 创建简历专用清洗转换器链
     * 包含隐私移除、冗余标签移除、空白规范化
     */
    public List<DocumentTransformer> createResumeCleaningTransformers() {
        return List.of(
                createPrivacyRemovalTransformer(),
                createRedundantLabelRemovalTransformer(),
                createWhitespaceNormalizationTransformer()
        );
    }

    /**
     * 智能截断文本（基于语义，而非简单字符截断）
     * 使用 Spring AI Document 在段落边界截断
     * 
     * @param content 原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
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
}

