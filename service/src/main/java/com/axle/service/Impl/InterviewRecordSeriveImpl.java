package com.axle.service.Impl;


import com.axle.base.BaseInfoProperties;
import com.axle.mapper.InterviewRecordMapper;
import com.axle.mapper.InterviewRecordMapperCustom;
import com.axle.pojo.InterviewRecord;
import com.axle.service.InterviewRecordService;
import com.axle.utils.PagedGridResult;
import com.axle.vo.InterviewRecordVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InterviewRecordSeriveImpl extends BaseInfoProperties implements InterviewRecordService {

    @Resource
    private InterviewRecordMapper  interviewRecordMapper;

    @Resource
    private InterviewRecordMapperCustom   interviewRecordMapperCustom;

    @Override
    public void save(InterviewRecord interviewRecord) {
        interviewRecordMapper.insert(interviewRecord);
    }

    @Override
    public Boolean selectcount(String candidateId) {
        List<InterviewRecord>  record = interviewRecordMapper.selectList(
                new QueryWrapper<InterviewRecord>()
                .eq("candidate_id",candidateId));
        if(record.isEmpty()||record.size()==0){
            return false;
        }
        return true;
    }

    @Override
    public PagedGridResult queryAllRecords(String realName, String mobile, Integer page, Integer pageSize) {
        PageHelper.startPage(page,pageSize);
        Map<String,Object> map = new HashMap<>();
        map.put("realName",realName);
        map.put("mobile",mobile);
        List<InterviewRecordVO> list = interviewRecordMapperCustom.queryAllRecords(map);
        return setterPagedGrid(list,page);
    }
}
