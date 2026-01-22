package com.axle.controller;

import com.axle.graceresult.GraceJSONResult;
import com.axle.service.DynamicInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 动态提问控制器
 * 仅支持SSE流式输出，移除冗余的同步接口
 */
@RestController
@RequestMapping("/dynamicInterview")
@Slf4j
public class DynamicInterviewController {

    @Resource
    private DynamicInterviewService dynamicInterviewService;

    /**
     * 开始动态面试（生成第一题）- 真正的流式输出
     */
    @GetMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startInterview(@RequestParam("candidateId") String candidateId,
                                     @RequestParam("jobId") String jobId) {
        log.info("【动态面试-流式】开始面试，候选人ID: {}, 职位ID: {}", candidateId, jobId);

        if (StringUtils.isBlank(candidateId) || StringUtils.isBlank(jobId)) {
            throw new IllegalArgumentException("候选人ID和职位ID不能为空");
        }

        SseEmitter emitter = new SseEmitter(120000L); // 120秒超时

        // 设置回调
        emitter.onCompletion(() -> log.info("【动态面试-流式】SSE连接完成"));
        emitter.onTimeout(() -> log.warn("【动态面试-流式】SSE连接超时"));
        emitter.onError(e -> log.error("【动态面试-流式】SSE连接错误", e));

        // 异步处理
        CompletableFuture.runAsync(() -> {
            try {
                log.info("【动态面试-流式】开始初始化上下文，候选人ID: {}, 职位ID: {}", candidateId, jobId);
                // 初始化上下文
                dynamicInterviewService.initInterviewContext(candidateId, jobId);
                log.info("【动态面试-流式】上下文初始化完成，开始生成问题");
                // 流式生成第一题
                dynamicInterviewService.generateQuestionStream(candidateId, null, null, emitter);
            } catch (Exception e) {
                log.error("【动态面试-流式】处理失败，候选人ID: {}, 错误: {}", candidateId, e.getMessage(), e);
                try {
                    sendErrorMessage(emitter, e.getMessage() != null ? e.getMessage() : "未知错误");
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    log.error("【动态面试-流式】发送错误消息失败", ex);
                }
            }
        });

        return emitter;
    }

    /**
     * 获取下一题（根据上一题的答案）- 真正的流式输出
     */
    @PostMapping(value = "/next", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter nextQuestion(@RequestParam("candidateId") String candidateId,
                                   @RequestParam("lastQuestion") String lastQuestion,
                                   @RequestParam("lastAnswer") String lastAnswer) {
        log.info("【动态面试-流式】获取下一题，候选人ID: {}", candidateId);

        if (StringUtils.isBlank(candidateId)) {
            throw new IllegalArgumentException("候选人ID不能为空");
        }

        SseEmitter emitter = new SseEmitter(120000L);

        emitter.onCompletion(() -> log.info("【动态面试-流式】下一题SSE连接完成"));
        emitter.onTimeout(() -> log.warn("【动态面试-流式】下一题SSE连接超时"));
        emitter.onError(e -> log.error("【动态面试-流式】下一题SSE连接错误", e));

        CompletableFuture.runAsync(() -> {
            try {
                dynamicInterviewService.generateQuestionStream(
                    candidateId, 
                    lastAnswer != null ? lastAnswer : "", 
                    lastQuestion != null ? lastQuestion : "",
                    emitter
                );
            } catch (Exception e) {
                log.error("【动态面试-流式】处理失败", e);
                sendErrorMessage(emitter, e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 清理面试上下文
     */
    @DeleteMapping("/clear")
    public GraceJSONResult clearInterview(@RequestParam("candidateId") String candidateId) {
        if (StringUtils.isBlank(candidateId)) {
            return GraceJSONResult.errorMsg("候选人ID不能为空");
        }
        dynamicInterviewService.clearInterviewContext(candidateId);
        return GraceJSONResult.ok("面试上下文已清理");
    }

    private void sendErrorMessage(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                .name("error")
                .data("{\"error\":\"" + escapeJson(message) + "\"}"));
        } catch (IOException ioException) {
            log.error("【动态面试-流式】发送错误信息失败", ioException);
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
