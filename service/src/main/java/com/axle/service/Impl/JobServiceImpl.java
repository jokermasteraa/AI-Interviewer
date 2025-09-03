package com.axle.service.Impl;

import com.axle.base.BaseInfoProperties;
import com.axle.bo.JobBO;
import com.axle.mapper.JobMapper;
import com.axle.mapper.JobMapperCustom;
import com.axle.pojo.Job;
import com.axle.service.JobService;
import com.axle.utils.PagedGridResult;
import com.axle.vo.JobVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

@Service
public class JobServiceImpl extends BaseInfoProperties implements JobService {

    @Resource
    JobMapper  jobMapper;
    @Resource
    JobMapperCustom   jobMapperCustom;

    @Override
    public void createOrUpdate(JobBO jobBO) {
        Job job = new Job();
        BeanUtils.copyProperties(jobBO, job);
        job.setUpdatedTime(LocalDateTime.now());
        if(StringUtils.isBlank(job.getId())){
            job.setCreateTime(LocalDateTime.now());
            jobMapper.insert(job);
        } else {
            job.setUpdatedTime(LocalDateTime.now());
            jobMapper.updateById(job);
        }

    }

    @Override
    public PagedGridResult queryAllJobLists(Integer page, Integer pageSize) {
        PageHelper.startPage(page,pageSize);
        List<JobVO> jobVoList = jobMapperCustom.queryAllJobList(new HashMap<>());
        return setterPagedGrid(jobVoList,page);
    }

    @Override
    public Job getDetail(String jobId) {
       return jobMapper.selectById(jobId);
    }

    @Override
    public void deleteById(String jobId) {
        jobMapper.deleteById(jobId);
    }

    @Override
    public boolean isJobContainInterviewer(String interviewerId) {
        QueryWrapper<Job> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("interviewer_id",interviewerId);
        Long count  = jobMapper.selectCount(queryWrapper);
        if(count > 0){
            return true;
        }
        return false;
    }

    @Override
    public List<HashMap<String,Object>> querynameList() {
        List<HashMap<String,Object>> map =jobMapperCustom.queryNameList(new HashMap<>());
        return map;
    }
}
