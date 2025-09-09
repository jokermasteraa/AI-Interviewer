package com.axle.controller;


import com.axle.ChatGLMTask;
import com.axle.graceresult.GraceJSONResult;
import com.axle.bo.SubmitAnswerBO;
import com.axle.service.InterviewRecordService;
import com.axle.utils.PagedGridResult;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/interviewRecord")
public class InterviewerRecordController {

    @Resource
    private ChatGLMTask  chatGLMTask;

    @Resource
    private InterviewRecordService interviewRecordService;

    @PostMapping("/collect")
    public GraceJSONResult collect(@RequestBody SubmitAnswerBO submitAnswerBO){
        chatGLMTask.display(submitAnswerBO);
        return GraceJSONResult.ok();
    }

    @GetMapping("/list")
    public GraceJSONResult list(@RequestParam String realName,
                                @RequestParam String mobile,
                                @RequestParam Integer page,
                                @RequestParam Integer pageSize){
        PagedGridResult result = interviewRecordService.queryAllRecords(realName,mobile,page,pageSize);
        return GraceJSONResult.ok(result);
    }
}
