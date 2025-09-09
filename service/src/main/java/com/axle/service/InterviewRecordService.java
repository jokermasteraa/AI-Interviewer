package com.axle.service;

import com.axle.pojo.InterviewRecord;
import com.axle.utils.PagedGridResult;

public interface InterviewRecordService {
    public void save(InterviewRecord interviewRecord);

    Boolean selectcount(String id);

    PagedGridResult queryAllRecords(String realName, String mobile, Integer page, Integer pageSize);
}
