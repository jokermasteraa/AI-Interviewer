package com.axle.service;

import com.axle.bo.CandidateBO;
import com.axle.graceresult.GraceJSONResult;
import com.axle.pojo.Candidate;
import com.axle.pojo.QuestionLib;
import com.axle.utils.PagedGridResult;

public interface CandidateService {
    void createOrUpdate(CandidateBO candidateBO);

    PagedGridResult queryAllCandidates(String realName, String mobile, Integer page, Integer pageSize);

    Candidate getDetail(String candidateId);

    void deleteById(String candidateId);

    Candidate queryByPhone(String mobile);

}
