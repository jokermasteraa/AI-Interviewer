package com.axle.controller;


//import com.axle.ChatGLMTask;
import com.axle.config.RabbitMQConfig;
import com.axle.graceresult.GraceJSONResult;
import com.axle.bo.SubmitAnswerBO;
import com.axle.service.InterviewRecordService;
import com.axle.utils.JsonUtils;
import com.axle.utils.PagedGridResult;
import jakarta.annotation.Resource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/interviewRecord")
public class InterviewerRecordController {

//    @Resource
//    private ChatGLMTask  chatGLMTask;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private InterviewRecordService interviewRecordService;

    @PostMapping("/collect")
    public GraceJSONResult collect(@RequestBody SubmitAnswerBO submitAnswerBO){
        // 原来的代码: chatGLMTask.display(submitAnswerBO);
        // 现在我们只发送消息

        String message = JsonUtils.objectToJson(submitAnswerBO);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_INTERVIEW,
                RabbitMQConfig.ROUTING_KEY_INTERVIEW,
                message);

        return GraceJSONResult.ok("您的面试结果正在分析中，请稍后查看。");
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
