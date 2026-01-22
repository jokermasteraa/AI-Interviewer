package com.axle.service;

import com.axle.pojo.InterviewRecord;
import com.axle.utils.PagedGridResult;

public interface InterviewRecordService {
    public void save(InterviewRecord interviewRecord);

    Boolean selectcount(String id);

    PagedGridResult queryAllRecords(String realName, String mobile, Integer page, Integer pageSize);
    
    /**
     * 根据候选人ID查询最新的面试记录
     */
    InterviewRecord getLatestByCandidateId(String candidateId);
    
    /**
     * 根据ID更新面试记录
     * @return 更新的行数
     */
    int updateById(InterviewRecord interviewRecord);
}
