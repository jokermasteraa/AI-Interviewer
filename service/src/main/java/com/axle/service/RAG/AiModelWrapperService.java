package com.axle.service.RAG;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * AI 模型调用封装
 * 提供统一的重试机制和限流保护
 */
@Service
@Slf4j
public class AiModelWrapperService {

    // 每秒最多调用 2 次 AI 接口，保护 API 额度
    private final RateLimiter rateLimiter = RateLimiter.create(250.0);

    /**
     * 同步调用 AI 模型并支持重试
     */
    @Retryable(
            value = { Exception.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000)
    )
    public String callAiModel(ChatModel model, Prompt prompt, String taskName) {
        log.info("【AI 调用】正在获取令牌，准备执行任务: {}", taskName);
        
        // 获取令牌，如果超过速率则阻塞等待
        rateLimiter.acquire();
        
        try {
            log.info("【AI 调用】令牌获取成功，开始调用大模型...");
            return model.call(prompt).getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("【AI 调用】任务 {} 失败: {}", taskName, e.getMessage());
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }
}
