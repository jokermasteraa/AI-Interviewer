package com.axle.controller;


import com.axle.enums.YesOrNo;
import com.axle.graceresult.GraceJSONResult;
import com.axle.bo.QuestionLibBO;
import com.axle.service.QuestionLibService;
import com.axle.utils.PagedGridResult;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/questionLib")
public class QuestionLibController {

    @Resource
    private QuestionLibService questionLibService;
    @PostMapping("/createOrUpdate")
    public GraceJSONResult createOrUpdate(@RequestBody  QuestionLibBO questionLibBO) {
        questionLibService.createOrUpdate(questionLibBO);
        return GraceJSONResult.ok();
    }

    @GetMapping("/list")
    public GraceJSONResult list(@RequestParam String aiName,
                                @RequestParam String question,
                                @RequestParam(defaultValue = "1") Integer page,
                                @RequestParam(defaultValue = "10") Integer pageSize){
        PagedGridResult gridResult = questionLibService.queryQuestionLibList(aiName, question, page, pageSize);
        return GraceJSONResult.ok(gridResult);
    }

    @PostMapping("/show")
    public GraceJSONResult show(@RequestParam String questionLibId){
        System.out.println("接收到的 questionLibId: " + questionLibId);
        if(StringUtils.isBlank(questionLibId)) return GraceJSONResult.error();
        questionLibService.showOrHide(questionLibId, YesOrNo.YES.type);
        return GraceJSONResult.ok();
    }

    @PostMapping("/hide")
    public GraceJSONResult hide(@RequestParam String  questionLibId){
        if(StringUtils.isBlank(questionLibId)) return GraceJSONResult.error();
        questionLibService.showOrHide(questionLibId, YesOrNo.NO.type);
        return GraceJSONResult.ok();
    }

    @PostMapping("/delete")
    public GraceJSONResult delete(@RequestParam String questionLibId){
        if (StringUtils.isBlank(questionLibId)) return GraceJSONResult.error();
        questionLibService.deleteById(questionLibId);
        return GraceJSONResult.ok();
    }

//    @RestControllerAdvice
//    public class GlobalExceptionHandler {
//
//        @ExceptionHandler(MissingServletRequestParameterException.class)
//        public GraceJSONResult handleMissingParam(MissingServletRequestParameterException e) {
//            System.err.println("缺少参数: " + e.getParameterName());
//            return GraceJSONResult.errorMsg("缺少必要参数: " + e.getParameterName());
//        }
//    }


}
