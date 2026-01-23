package com.axle.service.agent.workflow;

import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流上下文管理器
 * 用于在工作流任务和业务服务之间传递执行上下文
 */
@Component
public class InterviewWorkflowContext {

    private final Map<String, DelegateExecution> executionMap = new ConcurrentHashMap<>();

    /**
     * 设置当前执行上下文
     */
    public void setCurrentExecution(String candidateId, DelegateExecution execution) {
        executionMap.put(candidateId, execution);
    }

    /**
     * 获取当前执行上下文
     */
    public DelegateExecution getCurrentExecution(String candidateId) {
        return executionMap.get(candidateId);
    }

    /**
     * 移除执行上下文
     */
    public void removeExecution(String candidateId) {
        executionMap.remove(candidateId);
    }

    /**
     * 获取流程变量
     */
    public Object getVariable(String candidateId, String variableName) {
        DelegateExecution execution = getCurrentExecution(candidateId);
        if (execution != null) {
            return execution.getVariable(variableName);
        }
        return null;
    }

    /**
     * 设置流程变量
     */
    public void setVariable(String candidateId, String variableName, Object value) {
        DelegateExecution execution = getCurrentExecution(candidateId);
        if (execution != null) {
            execution.setVariable(variableName, value);
        }
    }
}
