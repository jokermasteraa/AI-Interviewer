package com.axle.service.Impl;


import com.axle.base.BaseInfoProperties;
import com.axle.enums.YesOrNo;
import com.axle.mapper.QuestionLibCustomMapper;
import com.axle.mapper.QuestionLibMapper;
import com.axle.pojo.Interviewer;
import com.axle.pojo.QuestionLib;
import com.axle.bo.QuestionLibBO;
import com.axle.vo.QuestionLibVO;
import com.axle.service.QuestionLibService;
import com.axle.utils.PagedGridResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QuestionLibServiceImpl extends BaseInfoProperties implements QuestionLibService {

    @Resource
    private QuestionLibMapper questionLibMapper;
    @Resource
    private QuestionLibCustomMapper questionLibCustomMapper;
    @Override
    public void createOrUpdate(QuestionLibBO questionLibBO) {
        QuestionLib questionLib = new QuestionLib();
        BeanUtils.copyProperties(questionLibBO,questionLib);
        questionLib.setUpdatedTime(LocalDateTime.now());
        if(StringUtils.isBlank(questionLib.getId())){
            questionLib.setIsOn(YesOrNo.YES.type);  //枚举
            questionLib.setCreateTime(LocalDateTime.now());
            questionLibMapper.insert(questionLib);
        }else {
            questionLibMapper.updateById(questionLib);
        }
    }

    @Override
    public PagedGridResult queryQuestionLibList(String aiName, String question, Integer page, Integer pageSize) {
        PageHelper.startPage(page,pageSize);
        Map<String,Object> map = new HashMap<>();
        if(StringUtils.isNotBlank(aiName)){
            map.put("aiName",aiName);
        }
        if(StringUtils.isNotBlank(question)){
            map.put("question",question);
        }
        List<QuestionLibVO> questionLibVOList = questionLibCustomMapper.queryQuestionLibList(map);
        return  setterPagedGrid(questionLibVOList,page);
    }

    @Override
    public void showOrHide(String questionLibId, Integer isOn) {
        QuestionLib questionLib = new QuestionLib();
        questionLib.setId(questionLibId);
        questionLib.setIsOn(isOn);
        questionLib.setUpdatedTime(LocalDateTime.now());
        questionLibMapper.updateById(questionLib);
    }

    @Override
    public void deleteById(String questionLibId) {
        QuestionLib questionLib = new QuestionLib();
        questionLib.setId(questionLibId);
        questionLibMapper.deleteById(questionLib);
    }

    @Override
    public boolean isQustionContainInterviewer(String interviewerId) {
        QueryWrapper<QuestionLib> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("interviewer_id", interviewerId);
        Long count = questionLibMapper.selectCount(queryWrapper);
        if( count > 0){
            return true;
        }
        return false;
    }
}
