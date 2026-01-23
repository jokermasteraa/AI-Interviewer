package com.axle.service.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 评估任务
 * 自动生成评分+技术评语，输出结构化报告
 */
@Component("evaluateTask")
@Slf4j
public class EvaluateTask implements JavaDelegate, ApplicationContextAware {

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
        
        log.info("【评估任务】执行，候选人ID: {}", candidateId);
        
        // 标记当前阶段
        execution.setVariable("currentStage", "EVALUATE_AND_FINISH");
        execution.setVariable("finished", true);
        
        // 将执行上下文保存到工作流上下文
        InterviewWorkflowContext context = getContext();
        context.setCurrentExecution(candidateId, execution);
        
        log.info("【评估任务】任务完成，面试已结束");
    }
}
