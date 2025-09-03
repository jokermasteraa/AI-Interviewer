package com.axle.controller;

import com.axle.bo.CandidateBO;
import com.axle.graceresult.GraceJSONResult;
import com.axle.service.CandidateService;
import com.axle.utils.PagedGridResult;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/candidate")
public class CandidateController {

    @Resource
    private CandidateService candidateService;

    @PostMapping("/createOrUpdate")
    public GraceJSONResult createOrUpdate(@RequestBody CandidateBO candidateBO) {
        candidateService.createOrUpdate(candidateBO);
        return GraceJSONResult.ok();
    }

    @GetMapping("/list")
    public GraceJSONResult list(@RequestParam String realName,
                                @RequestParam String mobile,
                                @RequestParam Integer page,
                                @RequestParam Integer pageSize) {
        PagedGridResult result = candidateService.queryAllCandidates(realName,mobile,page,pageSize);
        return GraceJSONResult.ok(result);
    }

    @GetMapping("/detail")
    public GraceJSONResult detail(String candidateId) {
        return GraceJSONResult.ok(candidateService.getDetail(candidateId));
    }

    @PostMapping("/delete")
    public GraceJSONResult delete(String candidateId) {
        candidateService.deleteById(candidateId);
        return GraceJSONResult.ok();
    }
}