package com.axle.service.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 简历初探任务
 * 提出1-2个基于简历的开放性问题
 */
@Component("resumeReviewTask")
@Slf4j
public class ResumeReviewTask implements JavaDelegate, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    private InterviewWorkflowContext getContext() {
        return applicationContext.getBean(InterviewWorkflowContext.class);
    }

    @Override
    public void execute(DelegateExecution execution) {
        String candidateId = (String) execution.getVariable("candidateId");
        
        log.info("【简历初探任务】执行，候选人ID: {}", candidateId);
        
        // 标记当前阶段
        execution.setVariable("currentStage", "RESUME_REVIEW");
        
        // 将执行上下文保存到工作流上下文，供后续服务使用
        InterviewWorkflowContext context = getContext();
        context.setCurrentExecution(candidateId, execution);
        
        log.info("【简历初探任务】任务完成，等待问题生成");
    }
}
