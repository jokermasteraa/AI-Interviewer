package com.axle.controller;


import com.axle.base.BaseInfoProperties;
import com.axle.enums.YesOrNo;
import com.axle.graceresult.GraceJSONResult;
import com.axle.bo.QuestionLibBO;
import com.axle.graceresult.ResponseStatusEnum;
import com.axle.service.QuestionLibService;
import com.axle.utils.PagedGridResult;
import com.axle.vo.InitQuestionLibVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/questionLib")
public class QuestionLibController extends BaseInfoProperties {

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
    public GraceJSONResult show(@RequestParam String questionId){
        System.out.println("接收到的 questionLibId: " + questionId);
        if(StringUtils.isBlank(questionId)) return GraceJSONResult.error();
        questionLibService.showOrHide(questionId, YesOrNo.YES.type);
        return GraceJSONResult.ok();
    }

    @PostMapping("/hide")
    public GraceJSONResult hide(@RequestParam String  questionId){
        if(StringUtils.isBlank(questionId)) return GraceJSONResult.error();
        questionLibService.showOrHide(questionId, YesOrNo.NO.type);
        return GraceJSONResult.ok();
    }

    @PostMapping("/delete")
    public GraceJSONResult delete(@RequestParam String questionId){
        if (StringUtils.isBlank(questionId)) return GraceJSONResult.error();
        questionLibService.deleteById(questionId);
        return GraceJSONResult.ok();
    }

    @GetMapping("/prepareQuestion")
    public GraceJSONResult prepareQuestion(@RequestParam String candidateId) {
        //判断应聘者是否在会话中，防止接口被恶意调用
        String userInfo = redis.get(REDIS_USER_INFO + candidateId);
        String userTOKEN = redis.get(REDIS_USER_TOKEN + candidateId);
        if(io.micrometer.common.util.StringUtils.isBlank(userInfo) || io.micrometer.common.util.StringUtils.isBlank(userTOKEN)){
            return GraceJSONResult.errorCustom(ResponseStatusEnum.USER_INFO_NOT_EXIST_ERROR);
        }
        List<InitQuestionLibVO> result = questionLibService.getRandomQuestions(candidateId,3);
        log.info("" + result);
        return GraceJSONResult.ok(result);
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
