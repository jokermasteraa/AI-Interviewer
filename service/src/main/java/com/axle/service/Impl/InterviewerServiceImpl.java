package com.axle.service.Impl;

import com.axle.mapper.InterviewerMapper;
import com.axle.pojo.Interviewer;
import com.axle.bo.Interviewerbo;
import com.axle.service.InterviewerService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class InterviewerServiceImpl implements InterviewerService {

    @Resource
    private InterviewerMapper interviewerMapper;

    @Override
    public void createOrUpdate(Interviewerbo interviewerbo) {
        Interviewer interviewer = new Interviewer();
        BeanUtils.copyProperties(interviewerbo,interviewer);
        interviewer.setUpdatedTime(LocalDateTime.now());
        if(StringUtils.isBlank(interviewer.getId())){
            interviewer.setCreateTime(LocalDateTime.now());
            interviewerMapper.insert(interviewer);
        }else {
            interviewerMapper.updateById(interviewer);
        }
    }

    @Override
    public List<Interviewer> queryAll() {
        return interviewerMapper.selectList(new QueryWrapper<Interviewer>().orderByAsc("updated_time"));
    }

    @Override
    public void deleteById(String InterviewerId) {
        //TODO 因为面试官与职位绑定，并且面试官下面还有许多题库，如果有，就不能被删除（多表关联需要删除关联的数据）
        interviewerMapper.deleteById(InterviewerId);
    }


}
