package com.axle.controller;


//import com.axle.ChatGLMTask;
import com.axle.config.RabbitMQConfig;
import com.axle.graceresult.GraceJSONResult;
import com.axle.bo.SubmitAnswerBO;
import com.axle.pojo.InterviewRecord;
import com.axle.service.InterviewRecordService;
import com.axle.utils.JsonUtils;
import com.axle.utils.PagedGridResult;
import com.axle.vo.ai.InterviewEvaluation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/interviewRecord")
@Slf4j
public class InterviewerRecordController {


    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private InterviewRecordService interviewRecordService;

    @PostMapping("/collect")
    public GraceJSONResult collect(@RequestBody SubmitAnswerBO submitAnswerBO){
        // 原来的代码: chatGLMTask.display(submitAnswerBO);
        // 现在我们只发送消息

        // 【性能测试】记录任务提交到RabbitMQ的时间戳
        long submitTimestamp = System.currentTimeMillis();
        String candidateId = submitAnswerBO != null ? submitAnswerBO.getCandidateId() : "unknown";
        log.info("【性能测试-任务提交】任务提交到RabbitMQ，candidateId: {}, timestamp: {}, time: {}", 
                candidateId, submitTimestamp, java.time.Instant.ofEpochMilli(submitTimestamp));

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
    
    /**
     * 查询候选人的面试报告
     */
    @GetMapping("/report")
    public GraceJSONResult getReport(@RequestParam String candidateId) {
        log.info("【面试报告】查询候选人报告，candidateId: {}", candidateId);
        
        InterviewRecord record = interviewRecordService.getLatestByCandidateId(candidateId);
        if (record == null) {
            return GraceJSONResult.errorMsg("面试报告尚未生成，请稍后再试");
        }
        
        // 解析 result 字段中的 JSON
        InterviewEvaluation evaluation = null;
        if (record.getResult() != null && !record.getResult().isEmpty()) {
            try {
                evaluation = JsonUtils.jsonToPojo(record.getResult(), InterviewEvaluation.class);
            } catch (Exception e) {
                log.error("【面试报告】解析评估结果失败", e);
            }
        }
        
        // 构建返回数据
        Map<String, Object> reportData = new java.util.HashMap<>();
        reportData.put("id", record.getId());
        reportData.put("jobName", record.getJobName());
        reportData.put("takeTime", record.getTakeTime());
        reportData.put("createTime", record.getCreateTime());
        reportData.put("answerAnalysis", record.getAnswerContent());  // 逐题分析
        reportData.put("evaluation", evaluation);  // 结构化评估结果
        
        // 推荐课程（可能为null，如果还在生成中）
        String recommendedCourses = record.getRecommendedCourses();
        log.info("【面试报告】推荐课程数据，recordId: {}, 是否存在: {}, 长度: {}", 
                record.getId(), recommendedCourses != null, 
                recommendedCourses != null ? recommendedCourses.length() : 0);
        reportData.put("recommendedCourses", recommendedCourses);  // 推荐课程
        
        return GraceJSONResult.ok(reportData);
    }
}
