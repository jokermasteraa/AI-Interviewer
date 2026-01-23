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
 * JD对齐深挖任务
 * 围绕JD要求的技术栈，至少覆盖3个核心维度
 */
@Component("jdAlignmentTask")
@Slf4j
public class JDAlignmentTask implements JavaDelegate, ApplicationContextAware {

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
        
        log.info("【JD对齐任务】执行，候选人ID: {}", candidateId);
        
        // 标记当前阶段
        execution.setVariable("currentStage", "JD_ALIGNMENT");
        
        // 检查是否应该结束
        Integer questionCount = (Integer) execution.getVariable("questionCount");
        List<String> coveredDimensions = (List<String>) execution.getVariable("coveredTechDimensions");
        List<String> jdTechDimensions = (List<String>) execution.getVariable("jdTechDimensions");
        
        // 判断是否应该结束
        boolean shouldEnd = shouldEndInterview(execution, questionCount, coveredDimensions, jdTechDimensions);
        execution.setVariable("shouldEnd", shouldEnd);
        execution.setVariable("shouldContinue", !shouldEnd);
        
        // 将执行上下文保存到工作流上下文
        InterviewWorkflowContext context = getContext();
        context.setCurrentExecution(candidateId, execution);
        
        log.info("【JD对齐任务】任务完成，问题数: {}, 是否结束: {}", questionCount, shouldEnd);
    }

    /**
     * 判断是否应该结束面试
     */
    private boolean shouldEndInterview(DelegateExecution execution, Integer questionCount,
                                       List<String> coveredDimensions, List<String> jdTechDimensions) {
        // 强制限制：最多8题
        if (questionCount >= 8) {
            log.warn("【JD对齐任务】问题数已达到上限（{}题），强制结束面试", questionCount);
            return true;
        }
        
        // 检查是否已覆盖至少3个JD技术维度，且问题数>=5
        if (questionCount >= 5 && coveredDimensions != null && jdTechDimensions != null) {
            int coveredJDDimensions = 0;
            for (String covered : coveredDimensions) {
                if (jdTechDimensions.contains(covered)) {
                    coveredJDDimensions++;
                }
            }
            
            if (coveredJDDimensions >= 3) {
                log.info("【JD对齐任务】已覆盖{}个JD技术维度，问题数{}题，可以结束面试", 
                        coveredJDDimensions, questionCount);
                return true;
            }
        }
        
        // 如果问题数>=6，也倾向于结束
        if (questionCount >= 6) {
            log.info("【JD对齐任务】问题数已足够（{}题），可以结束面试", questionCount);
            return true;
        }
        
        return false;
    }
}
