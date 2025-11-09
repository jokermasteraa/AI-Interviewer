package com.axle.controller;

import com.axle.bo.NextQuestionResponse;
import com.axle.graceresult.GraceJSONResult;
import com.axle.service.LiveInterviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/live")
public class LiveInterviewController {

    @Resource
    private LiveInterviewService liveInterviewService;

    /**
     * 开始一场"一问一答"面试
     * @param candidateId 候选人ID (假设已通过 /welcome/verify 登录)
     */
    @PostMapping("/start")
    public GraceJSONResult start(@RequestParam String candidateId) {
        String firstQuestion = liveInterviewService.startInterview(candidateId);
        return GraceJSONResult.ok(firstQuestion);
    }

    /**
     * 提交一个回答，并获取下一个问题
     */
    @PostMapping("/next")
    public GraceJSONResult next(@RequestParam String candidateId, @RequestParam String answer) {
        NextQuestionResponse response = liveInterviewService.postAnswerAndGetNext(candidateId, answer);
        return GraceJSONResult.ok(response);
    }

    /**
     * 结束面试并获取总结报告
     * (在 /next 接口返回 is_finished: true 后调用)
     */
    @PostMapping("/summary")
    public GraceJSONResult summary(@RequestParam String candidateId) {
        return GraceJSONResult.ok(liveInterviewService.generateSummary(candidateId));
    }

}