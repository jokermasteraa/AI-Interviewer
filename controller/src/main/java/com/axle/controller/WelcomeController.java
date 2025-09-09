package com.axle.controller;

import com.axle.base.BaseInfoProperties;
import com.axle.bo.VerifySMSBO;
import com.axle.graceresult.GraceJSONResult;
import com.axle.graceresult.ResponseStatusEnum;
import com.axle.pojo.Candidate;
import com.axle.service.CandidateService;
import com.axle.service.InterviewRecordService;
import com.axle.vo.CandidateVO;
import com.axle.vo.InitCandidateVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.simpleframework.xml.core.Validate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.axle.utils.JsonUtils.objectToJson;

@RestController
@RequestMapping("/welcome")
public class WelcomeController extends BaseInfoProperties {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CandidateService candidateService;

    @Resource
    private InterviewRecordService interviewRecordService;

    @PostMapping("/getSMSCode")
    public GraceJSONResult  getSMSCode(@RequestParam String mobile){
        if(StringUtils.isBlank(mobile)){
            return GraceJSONResult.errorMsg("<UNK>");
        }
        String smsCode = (int)((Math.random()*9 + 1) * 100000)+"";
        //把验证码存入到redis中
        redis.set(MOBILE_SMSCODE + mobile , smsCode , 60);
        //stringRedisTemplate.opsForValue().set(MOBILE_SMSCODE+mobile,smsCode, 60, TimeUnit.SECONDS);
        System.out.println("smsCode:"+smsCode);
        return GraceJSONResult.ok();
    }

    @PostMapping("verify")
    public GraceJSONResult verify(@Valid @RequestBody VerifySMSBO verifySMSBO){
        //1.从Redis中拿取验证码看是否匹配
        String mobile = verifySMSBO.getMobile();
        String smsCode = verifySMSBO.getSmsCode();
        String redisCode = redis.get(MOBILE_SMSCODE + mobile);
        if(StringUtils.isBlank(redisCode)||!redisCode.equals(smsCode)){
            return GraceJSONResult.errorMsg("验证码已过期或验证码输入错误");
        }
        //2.根据mobile查询数据库，查看是否存在
        Candidate candidate = candidateService.queryByPhone(mobile);
        if(StringUtils.isBlank(candidate.getId())){
            //2.1不存在则代表没有该候选人
            return GraceJSONResult.errorCustom(ResponseStatusEnum.USER_INFO_NOT_EXIST_ERROR);
        } else {
            //2.2存在则查看是否已经面试过了，如果已经面试过了就不能在面试了 TODO需要知道是否已经面试过
            Boolean flag = interviewRecordService.selectcount(candidate.getId());
            if(flag){
                return GraceJSONResult.errorCustom(ResponseStatusEnum.USER_ALREADY_DID_INTERVIEW_ERROR);
            }
        }
        //3.保存TOKEN信息到Redis中
        String userTOKEN = UUID.randomUUID().toString();
        redis.set(REDIS_USER_TOKEN+candidate.getId(),userTOKEN,60*1*60);
        //使用VO来进行返回
        InitCandidateVO candidateVO = new InitCandidateVO();
        BeanUtils.copyProperties(candidate,candidateVO);
        candidateVO.setCandidateId(candidate.getId());
        //4.用户进入面试流程后，删除Redis中的验证码。
        redis.del(MOBILE_SMSCODE+mobile);
        //5.可将面试结果保存在Redis中几小时(可选)
        redis.set(REDIS_USER_INFO+candidateVO.getCandidateId(),objectToJson(candidateVO),60*1*60);
        //6.返回用户信息给前端
        return GraceJSONResult.ok(candidateVO);
    }
}
