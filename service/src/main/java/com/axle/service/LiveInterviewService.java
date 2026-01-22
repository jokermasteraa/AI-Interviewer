package com.axle.service;

import com.axle.vo.ai.InterviewEvaluation;

public interface LiveInterviewService {

    String startInterview(String candidateId);


    InterviewEvaluation generateSummary(String candidateId);
}