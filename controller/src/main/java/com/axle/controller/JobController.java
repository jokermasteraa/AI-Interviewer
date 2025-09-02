package com.axle.controller;


import com.axle.bo.JobBO;
import com.axle.graceresult.GraceJSONResult;
import com.axle.service.JobService;
import com.axle.utils.PagedGridResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


@Slf4j
@RestController
@RequestMapping("/job")
public class JobController {

    @Resource
    private JobService jobService;

    @PostMapping("/createOrUpdate")
    public GraceJSONResult createOrUpdate(@RequestBody JobBO jobBO){
        jobService.createOrUpdate(jobBO);
        return GraceJSONResult.ok();
    }

    @GetMapping("/list")
    public GraceJSONResult list(@RequestParam Integer page, @RequestParam Integer pageSize){
        PagedGridResult result = jobService.queryAllJobLists(page,pageSize);
        return GraceJSONResult.ok(result);
    }

    @GetMapping("/detail")
    public GraceJSONResult detail( String jobId){
        log.info("jobId:{}",jobId);
        return GraceJSONResult.ok(jobService.getDetail(jobId));
    }

    @PostMapping("/delete")
    public GraceJSONResult delete(String jobId){
        jobService.deleteById(jobId);
        return GraceJSONResult.ok();
    }


}
