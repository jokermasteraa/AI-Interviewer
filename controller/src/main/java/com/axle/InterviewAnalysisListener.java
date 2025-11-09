package com.axle;

import com.axle.bo.SubmitAnswerBO;
import com.axle.config.RabbitMQConfig;
import com.axle.service.RAG.ChatGLMService;
import com.axle.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
@Slf4j
public class InterviewAnalysisListener {

    // 注入我们抽离出的ChatGLMService
    @Autowired
    public ChatGLMService chatGLMService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_INTERVIEW)
    public void processInterviewAnalysis(Message message) {

        Instant startTime = Instant.now();
        log.info("【RabbitMQ消费者】接收到AI分析任务... 开始计时。");

        try {
            String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
            SubmitAnswerBO submitAnswerBO = JsonUtils.jsonToPojo(messageBody, SubmitAnswerBO.class);

            if (submitAnswerBO != null) {
                // 调用核心业务逻辑
                chatGLMService.analyze(submitAnswerBO);
                log.info("【RabbitMQ消费者】AI分析任务处理完成，候选人ID: {}", submitAnswerBO.getCandidateId());
            } else {
                log.error("【RabbitMQ消费者】消息体解析失败: {}", messageBody);
            }
        } catch (Exception e) {
            log.error("【RabbitMQ消费者】处理AI分析任务时发生异常", e);
            // 可以在这里加入重试或死信队列逻辑
        } finally {
            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);

            // 打印总耗时（包括了所有Java逻辑和AI调用）
            log.info("【RabbitMQ消费者】任务处理完毕。总耗时: {} 秒 ({} 毫秒)",
                    duration.toSeconds(),
                    duration.toMillis());
        }
    }
}