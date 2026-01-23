package com.axle.service.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生成问题任务
 * LLM生成面试问题，并决策是否继续面试
 */
@Component("generateQuestionTask")
@Slf4j
public class GenerateQuestionTask implements JavaDelegate, ApplicationContextAware {

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
        
        log.info("【生成问题任务】执行，候选人ID: {}", candidateId);
        
        // 标记当前阶段，等待业务代码生成问题
        execution.setVariable("currentStage", "GENERATE_QUESTION");
        
        // 设置默认值，避免decisionGateway无法选择路径
        // 默认继续面试，业务代码会在生成问题时根据LLM决策更新这些值
        Boolean shouldContinue = (Boolean) execution.getVariable("shouldContinue");
        Boolean shouldEnd = (Boolean) execution.getVariable("shouldEnd");
        if (shouldContinue == null) {
            execution.setVariable("shouldContinue", true);
        }
        if (shouldEnd == null) {
            execution.setVariable("shouldEnd", false);
        }
        
        // 将执行上下文保存到工作流上下文，供业务代码使用
        InterviewWorkflowContext context = getContext();
        context.setCurrentExecution(candidateId, execution);
        
        log.info("【生成问题任务】任务完成，等待业务代码生成问题");
    }
}
