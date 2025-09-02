package com.axle.service;

import com.axle.bo.JobBO;
import com.axle.pojo.Job;
import com.axle.utils.PagedGridResult;

public interface JobService {

    void createOrUpdate(JobBO jobBO);

    PagedGridResult queryAllJobLists(Integer page, Integer pageSize);

   public Job getDetail(String jobId);

    void deleteById(String jobId);

    boolean isJobContainInterviewer(String interviewerId);
}
