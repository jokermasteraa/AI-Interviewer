package com.axle.service.Impl;


import com.axle.base.BaseInfoProperties;
import com.axle.bo.CandidateBO;
import com.axle.mapper.CandidateMapper;
import com.axle.mapper.CandidateMapperCustom;
import com.axle.pojo.Candidate;
import com.axle.service.CandidateService;
import com.axle.utils.PagedGridResult;
import com.axle.vo.CandidateVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CandidateServiceImpl extends BaseInfoProperties implements CandidateService {

    @Resource
    private CandidateMapper  candidateMapper;
    @Resource
    private CandidateMapperCustom  candidateMapperCustom;
    @Override
    public void createOrUpdate(@RequestBody CandidateBO candidateBO) {
        Candidate candidate = new Candidate();
        BeanUtils.copyProperties(candidateBO, candidate);
        candidate.setUpdatedTime(LocalDateTime.now());
        if(StringUtils.isBlank(candidate.getId())){
            candidate.setCreatedTime(LocalDateTime.now());
            candidateMapper.insert(candidate);
        } else {
            candidateMapper.updateById(candidate);
        }
    }

    @Override
    public PagedGridResult queryAllCandidates(String realName, String mobile, Integer page, Integer pageSize) {
        PageHelper.startPage(page, pageSize);
        Map<String,Object> map = new HashMap<>();
        map.put("realName",realName);
        map.put("mobile",mobile);
        List<CandidateVO> candidateVOS = candidateMapperCustom.queryAllcandidate(map);
        return setterPagedGrid(candidateVOS,page);
    }

    @Override
    public Candidate getDetail(String candidateId) {
        return candidateMapper.selectById(candidateId);
    }

    @Override
    public void deleteById(String candidateId) {
        candidateMapper.deleteById(candidateId);
    }


}
