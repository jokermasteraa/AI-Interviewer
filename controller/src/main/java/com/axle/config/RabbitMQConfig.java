package com.axle.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    // ========== 主业务交换机和队列 ==========
    public static final String EXCHANGE_INTERVIEW = "exchange_interview_analysis";
    public static final String QUEUE_INTERVIEW = "queue_interview_analysis";
    public static final String ROUTING_KEY_INTERVIEW = "routing.key.interview.analysis";

    // ========== 死信交换机和队列 (DLX/DLQ) ==========
    public static final String EXCHANGE_INTERVIEW_DLX = "exchange_interview_analysis_dlx";
    public static final String QUEUE_INTERVIEW_DLQ = "queue_interview_analysis_dlq";
    public static final String ROUTING_KEY_INTERVIEW_DLQ = "routing.key.interview_dlq";

    /**
     * 1. 主业务交换机
     */
    @Bean
    public TopicExchange exchangeInterview() {
        return new TopicExchange(EXCHANGE_INTERVIEW, true, false);
    }

    /**
     * 2. 主业务队列 (关键：绑定死信交换机)
     */
    @Bean
    public Queue queueInterview() {
        Map<String, Object> args = new HashMap<>();
        // 绑定死信交换机 (DLX)
        args.put("x-dead-letter-exchange", EXCHANGE_INTERVIEW_DLX);
        // 绑定死信路由键
        args.put("x-dead-letter-routing-key", ROUTING_KEY_INTERVIEW_DLQ);

        return new Queue(QUEUE_INTERVIEW, true, false, false, args);
    }

    /**
     * 3. 主业务绑定
     */
    @Bean
    public Binding bindingInterview() {
        return BindingBuilder.bind(queueInterview()).to(exchangeInterview()).with(ROUTING_KEY_INTERVIEW);
    }

    // ========== 4. 配置死信交换机 (DLX) ==========
    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange(EXCHANGE_INTERVIEW_DLX, true, false);
    }

    // ========== 5. 配置死信队列 (DLQ) ==========
    @Bean
    public Queue dlqQueue() {
        return new Queue(QUEUE_INTERVIEW_DLQ, true, false, false);
    }

    // ========== 6. 配置死信绑定 ==========
    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(ROUTING_KEY_INTERVIEW_DLQ);
    }
}