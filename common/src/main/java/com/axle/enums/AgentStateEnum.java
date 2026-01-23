package com.axle.enums;

/**
 * Agent面试状态枚举
 */
public enum AgentStateEnum {
    INIT("INIT", "初始化（加载简历+JD，解析关键技术点）"),
    RESUME_REVIEW("RESUME_REVIEW", "简历初探（提出1-2个基于简历的开放性问题）"),
    JD_ALIGNMENT("JD_ALIGNMENT", "JD对齐深挖（围绕JD要求的技术栈，至少覆盖3个核心维度）"),
    EVALUATE_AND_FINISH("EVALUATE_AND_FINISH", "评估并结束（自动生成评分+技术评语，输出结构化报告）"),
    // 保留旧状态以兼容（已废弃）
    SELF_INTRO("SELF_INTRO", "请候选人自我介绍（已废弃）"),
    TECH_DEEP_DIVE("TECH_DEEP_DIVE", "技术深度追问（已废弃）"),
    BEHAVIORAL("BEHAVIORAL", "行为问题（已废弃）"),
    EVALUATING("EVALUATING", "内部评估打分（已废弃）"),
    FINISHED("FINISHED", "生成报告并结束（已废弃）");

    public final String code;
    public final String description;

    AgentStateEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static AgentStateEnum getByCode(String code) {
        if (code == null) return null;
        for (AgentStateEnum state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        return null;
    }
}
