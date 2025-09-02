package com.axle.controller;

import com.axle.graceresult.GraceJSONResult;
import com.axle.bo.Interviewerbo;
import com.axle.service.InterviewerService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/interviewer")
public class InterviewerController {

    @Resource
    private InterviewerService interviewerService;
    @PostMapping("/createOrUpdate")
    public GraceJSONResult createOrUpdate(@Valid @RequestBody  Interviewerbo interviewerbo){
        interviewerService.createOrUpdate(interviewerbo);
        return GraceJSONResult.ok();
    }

    @GetMapping("/list")
    public GraceJSONResult list(String id){
        return GraceJSONResult.ok( interviewerService.queryAll());
    }

    @DeleteMapping("/delete")
    public GraceJSONResult delete(@RequestParam  String interviewerId){
        interviewerService.deleteById(interviewerId);
        return  GraceJSONResult.ok();
    }
}
