package com.axle.service;

import com.axle.bo.NextQuestionResponse;
import com.axle.vo.ai.InterviewEvaluation;

public interface LiveInterviewService {

    String startInterview(String candidateId);

    NextQuestionResponse postAnswerAndGetNext(String candidateId, String lastAnswer);

    InterviewEvaluation generateSummary(String candidateId);
}