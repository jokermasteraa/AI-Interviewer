package com.axle.base;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Collections;

@Configuration
public class RetryConfig {

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // 设置重试策略：最多重试3次，只对NonTransientAiException进行重试
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3,
                Collections.singletonMap(org.springframework.ai.retry.NonTransientAiException.class, true));
        retryTemplate.setRetryPolicy(retryPolicy);

        // 设置退避策略：指数退避，初始等待1000毫秒，最大等待10000毫秒
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000); // 首次重试等待1秒
        backOffPolicy.setMultiplier(2.0);      // 每次重试等待时间翻倍
        backOffPolicy.setMaxInterval(10000);   // 最大等待时间10秒

        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }
}