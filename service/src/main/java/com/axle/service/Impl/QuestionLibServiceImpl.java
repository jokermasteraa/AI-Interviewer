package com.axle.service.Impl;


import com.axle.base.BaseInfoProperties;
import com.axle.enums.YesOrNo;
import com.axle.mapper.*;
import com.axle.pojo.Interviewer;
import com.axle.pojo.Job;
import com.axle.pojo.QuestionLib;
import com.axle.bo.QuestionLibBO;
import com.axle.vo.InitQuestionLibVO;
import com.axle.vo.QuestionLibVO;
import com.axle.service.QuestionLibService;
import com.axle.utils.PagedGridResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class QuestionLibServiceImpl extends BaseInfoProperties implements QuestionLibService {

    @Resource
    private QuestionLibMapper questionLibMapper;
    @Resource
    private QuestionLibCustomMapper questionLibCustomMapper;
    @Resource
    private JobMapper  jobMapper;
    @Resource
    private CandidateMapper  candidateMapper;

    @Override
    public void createOrUpdate(QuestionLibBO questionLibBO) {
        QuestionLib questionLib = new QuestionLib();
        BeanUtils.copyProperties(questionLibBO,questionLib);
        // DB 字段 reference_answer 非空，这里兜底为空字符串，避免插入失败
        if (questionLib.getReferenceAnswer() == null) {
            questionLib.setReferenceAnswer("");
        }
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
    public void deleteById(@RequestParam String questionLibId) {
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



    @Override
    public List<InitQuestionLibVO> getRandomQuestions(String candidateId, Integer questionNum) {
        //1.获取候选者所对应的面试官
        String jobId = candidateMapper.selectById(candidateId).getJobId();
        //Job job = jobMapper.selectById(candidateId);
        String interviewerId = jobMapper.selectById(jobId).getInterviewerId();
        //2.得到面试官的总体数
        Long count = questionLibMapper.selectCount(new QueryWrapper<QuestionLib>().eq("interviewer_id", interviewerId));
        
        // 检查题库是否有题目
        if (count == null || count <= 0) {
            log.warn("【题库查询】面试官ID: {} 的题库为空", interviewerId);
            return new ArrayList<>();
        }
        
        //3.根据题库总数获得指定数量的面试题
        List<Long> randomList =  new  ArrayList<>();
        Random random = new Random();

            for(int i = 0; i < questionNum; i++){
                long randomIndex = random.nextLong(count);
                if(randomList.contains(randomIndex)){
                    questionNum++;
                } else {
                    randomList.add(randomIndex);
                }
            }

        //4.根据索引下标获得面试题
        List<InitQuestionLibVO>  initQuestionLibVOList = new ArrayList<>();
        for(long i : randomList){
            Map<String,Object> map = new HashMap<>();
            map.put("randomIndex",i);
            InitQuestionLibVO question = questionLibCustomMapper.queryRandomQuestion(map);
            initQuestionLibVOList.add(question);
        }
        return initQuestionLibVOList;
    }




}
