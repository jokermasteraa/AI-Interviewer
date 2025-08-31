package com.axle.service;

import com.axle.pojo.Interviewer;
import com.axle.pojo.bo.Interviewerbo;

import java.util.List;


public interface InterviewerService {

    public void createOrUpdate(Interviewerbo interviewerbo);
    public List<Interviewer> queryAll();
    public void deleteById(String interviewerId);
}
