package com.axle.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_INTERVIEW = "exchange_interview_analysis";
    public static final String QUEUE_INTERVIEW = "queue_interview_analysis";
    public static final String ROUTING_KEY_INTERVIEW = "routing.key.interview.analysis";

    @Bean
    public TopicExchange exchangeInterview() {
        return new TopicExchange(EXCHANGE_INTERVIEW, true, false);
    }

    @Bean
    public Queue queueInterview() {
        return new Queue(QUEUE_INTERVIEW, true, false, false);
    }

    @Bean
    public Binding bindingInterview() {
        return BindingBuilder.bind(queueInterview()).to(exchangeInterview()).with(ROUTING_KEY_INTERVIEW);
    }
}