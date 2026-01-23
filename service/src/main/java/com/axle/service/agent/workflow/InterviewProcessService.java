package com.axle.service.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 面试流程服务
 * 负责启动和管理Flowable工作流流程实例
 */
@Service
@Slf4j
public class InterviewProcessService {

    @Autowired
    private RuntimeService runtimeService;

    private static final String PROCESS_DEFINITION_KEY = "interviewProcess";

    /**
     * 启动面试流程实例
     * 
     * @param candidateId 候选人ID
     * @param jobId 职位ID
     * @return 流程实例ID
     */
    public String startInterviewProcess(String candidateId, String jobId) {
        log.info("【面试流程】启动流程实例，候选人ID: {}, 职位ID: {}", candidateId, jobId);
        
        // 先检查是否有已存在的流程实例，如果有则删除
        java.util.List<ProcessInstance> existingInstances = runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(candidateId)
            .active()
            .orderByStartTime()
            .desc()
            .list();
        
        if (!existingInstances.isEmpty()) {
            log.warn("【面试流程】发现已存在的流程实例（{}个），将删除旧实例", existingInstances.size());
            for (ProcessInstance instance : existingInstances) {
                try {
                    runtimeService.deleteProcessInstance(instance.getId(), "重新启动面试流程");
                    log.info("【面试流程】已删除旧流程实例: {}", instance.getId());
                } catch (Exception e) {
                    log.error("【面试流程】删除旧流程实例失败: {}", instance.getId(), e);
                }
            }
        }
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("candidateId", candidateId);
        variables.put("jobId", jobId);
        variables.put("questionCount", 0);
        variables.put("shouldContinue", false);
        variables.put("shouldEnd", false);
        
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(
            PROCESS_DEFINITION_KEY, 
            candidateId, // 使用candidateId作为businessKey
            variables
        );
        
        log.info("【面试流程】流程实例启动成功，流程实例ID: {}, 业务键: {}", 
                processInstance.getId(), processInstance.getBusinessKey());
        
        return processInstance.getId();
    }

    /**
     * 继续流程（提交答案后触发下一步）
     * 
     * @param candidateId 候选人ID
     * @param answer 答案
     */
    public void continueProcess(String candidateId, String answer) {
        log.info("【面试流程】继续流程，候选人ID: {}, 答案长度: {}", 
                candidateId, answer != null ? answer.length() : 0);
        
        ProcessInstance processInstance = getProcessInstance(candidateId);
        
        if (processInstance == null) {
            log.warn("【面试流程】未找到流程实例，候选人ID: {}", candidateId);
            return;
        }
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("lastAnswer", answer);
        
        // 确保shouldContinue和shouldEnd已设置（如果未设置，使用默认值）
        Boolean shouldContinue = (Boolean) runtimeService.getVariable(processInstance.getId(), "shouldContinue");
        Boolean shouldEnd = (Boolean) runtimeService.getVariable(processInstance.getId(), "shouldEnd");
        if (shouldContinue == null) {
            shouldContinue = true; // 默认继续
            variables.put("shouldContinue", shouldContinue);
            log.warn("【面试流程】shouldContinue未设置，使用默认值: true");
        }
        if (shouldEnd == null) {
            shouldEnd = false; // 默认不结束
            variables.put("shouldEnd", shouldEnd);
            log.warn("【面试流程】shouldEnd未设置，使用默认值: false");
        }
        
        // 设置流程变量
        runtimeService.setVariables(processInstance.getId(), variables);
        
        // 查找当前活动的接收任务并触发
        org.flowable.engine.runtime.Execution execution = runtimeService.createExecutionQuery()
            .processInstanceId(processInstance.getId())
            .activityId("waitForAnswer")
            .singleResult();
        
        if (execution != null) {
            // 触发接收任务继续执行
            runtimeService.trigger(execution.getId());
            log.info("【面试流程】流程继续执行，执行ID: {}, shouldContinue: {}, shouldEnd: {}", 
                    execution.getId(), shouldContinue, shouldEnd);
        } else {
            log.warn("【面试流程】未找到活动的接收任务，流程实例ID: {}", processInstance.getId());
        }
    }

    /**
     * 获取流程实例
     * 如果有多个流程实例，返回最新的活动实例
     * 
     * @param candidateId 候选人ID
     * @return 流程实例
     */
    public ProcessInstance getProcessInstance(String candidateId) {
        java.util.List<ProcessInstance> instances = runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(candidateId)
            .active() // 只查询活动的流程实例
            .orderByStartTime()
            .desc() // 按开始时间降序排列，最新的在前
            .list();
        
        if (instances.isEmpty()) {
            return null;
        }
        
        if (instances.size() > 1) {
            log.warn("【面试流程】发现多个活动流程实例（{}个），使用最新的: {}", 
                    instances.size(), instances.get(0).getId());
        }
        
        return instances.get(0); // 返回最新的一个
    }

    /**
     * 检查流程是否已结束
     * 
     * @param candidateId 候选人ID
     * @return 是否已结束
     */
    public boolean isProcessFinished(String candidateId) {
        ProcessInstance processInstance = getProcessInstance(candidateId);
        return processInstance == null;
    }

    /**
     * 设置流程变量
     * 
     * @param candidateId 候选人ID
     * @param variableName 变量名
     * @param value 变量值
     */
    public void setVariable(String candidateId, String variableName, Object value) {
        ProcessInstance processInstance = getProcessInstance(candidateId);
        if (processInstance != null) {
            runtimeService.setVariable(processInstance.getId(), variableName, value);
        }
    }

    /**
     * 获取流程变量
     * 
     * @param candidateId 候选人ID
     * @param variableName 变量名
     * @return 变量值
     */
    public Object getVariable(String candidateId, String variableName) {
        ProcessInstance processInstance = getProcessInstance(candidateId);
        if (processInstance != null) {
            return runtimeService.getVariable(processInstance.getId(), variableName);
        }
        return null;
    }

    /**
     * 获取RuntimeService（供外部使用）
     */
    public RuntimeService getRuntimeService() {
        return runtimeService;
    }

    /**
     * 检查流程是否在等待接收任务
     * 
     * @param candidateId 候选人ID
     * @return 是否在等待
     */
    public boolean isWaitingForAnswer(String candidateId) {
        ProcessInstance processInstance = getProcessInstance(candidateId);
        if (processInstance == null) {
            return false;
        }
        
        // 检查是否在等待接收任务
        org.flowable.engine.runtime.Execution execution = runtimeService.createExecutionQuery()
            .processInstanceId(processInstance.getId())
            .activityId("waitForAnswer")
            .singleResult();
        
        return execution != null;
    }
}
