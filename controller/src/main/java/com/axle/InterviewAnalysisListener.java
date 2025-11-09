package com.axle;

import com.axle.bo.SubmitAnswerBO;
import com.axle.config.RabbitMQConfig;
import com.axle.mapper.InterviewRecordMapper;
import com.axle.pojo.InterviewRecord;
import com.axle.service.InterviewRecordService;
import com.axle.service.RAG.ChatGLMService;
import com.axle.utils.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import com.rabbitmq.client.Channel;

@Component
@Slf4j
public class InterviewAnalysisListener {

    @Autowired
    public ChatGLMService chatGLMService;

    @Autowired
    public InterviewRecordMapper interviewRecordMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_INTERVIEW)
    public void processInterviewAnalysis(Message message, Channel channel,
                                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException { // 1. 修改方法签名

        Instant startTime = Instant.now();
        log.info("【RabbitMQ消费者】接收到AI分析任务... deliveryTag: {}", deliveryTag);
        String messageBody = null;

        try {
            messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
            SubmitAnswerBO submitAnswerBO = JsonUtils.jsonToPojo(messageBody, SubmitAnswerBO.class);

            // 幂等性检查 (复用在 WelcomeController 中的逻辑)
            Boolean alreayExists = interviewRecordMapper.exists(new QueryWrapper<InterviewRecord>().eq("candidate_id",submitAnswerBO.getCandidateId()));
            if (alreayExists) {
                log.warn("【幂等性检查】该面试已被处理，忽略重复消息。CandidateId: {}", submitAnswerBO.getCandidateId());
                channel.basicAck(deliveryTag, false); // 确认消息，防止重复进入DLQ
                return; // 结束处理
            }

// 正常执行
            chatGLMService.analyze(submitAnswerBO);
            channel.basicAck(deliveryTag, false);

            if (submitAnswerBO != null) {
                chatGLMService.analyze(submitAnswerBO);
                log.info("【RabbitMQ消费者】AI分析任务处理完成，ID: {}", submitAnswerBO.getCandidateId());

                // 2. 成功：手动发送 ACK
                channel.basicAck(deliveryTag, false);

            } else {
                log.error("【RabbitMQ消费者】消息体解析失败: {}", messageBody);
                // 3. 失败 (可恢复的)：发送 NACK，让其进入DLQ
                channel.basicNack(deliveryTag, false, false); // (multiple=false, requeue=false)
            }
        } catch (Exception e) {
            log.error("【RabbitMQ消费者】处理AI分析任务时发生异常。消息将进入DLQ。消息体: {}", messageBody, e);

            // 3. 失败 (不可恢复的)：发送 NACK，让其进入DLQ
            // 最后一个 false (requeue=false) 至关重要，它告诉RabbitMQ不要重试，而是发往DLQ
            channel.basicNack(deliveryTag, false, false);
        }finally {
            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);
            log.info("【RabbitMQ消费者】任务处理完毕。总耗时: {} 秒 ({} 毫秒)",
                    duration.toSeconds(),
                    duration.toMillis());
        }
    }

    /**
     * 2. 死信队列 (DLQ) 监听器
     * 用于接收、记录处理失败的消息
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_INTERVIEW_DLQ)
    public void processInterviewDLQ(Message message) {
        String failedMessageBody = new String(message.getBody(), StandardCharsets.UTF_8);

        log.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        log.error("【死信队列 - DLQ】接收到一条无法处理的消息!");
        log.error("【死信队列 - DLQ】消息体: {}", failedMessageBody);
        log.error("【死信队列 - DLQ】请检查以上日志，进行人工排查。");
        log.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

        // 在这里，可以将 failedMessageBody 存储到数据库的“失败任务表”中，
        // 而不是仅仅打印日志，以便后续进行人工处理或重试。
    }
}