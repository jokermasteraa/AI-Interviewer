package com.axle.base;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {

    // 从 application.yml 读取配置，如果没配，默认值是 10
    @Value("${ai.analysis.pool-size:500}")
    private int aiAnalysisPoolSize;

    /**
     * 定义用于 AI 分析的专用线程池
     * 我们给这个 Bean 一个特定的名字 "aiAnalysisExecutor"，以便在 ChatGLMService 中精确注入
     */
    @Bean("aiAnalysisExecutor")
    public ExecutorService aiAnalysisExecutor() {
        // 使用配置的值来创建线程池
        return Executors.newFixedThreadPool(aiAnalysisPoolSize);
    }
}