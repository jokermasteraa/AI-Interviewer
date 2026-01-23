package com.axle.enums;

/**
 * 面试模式枚举
 */
public enum InterviewModeEnum {
    RESUME_BASED(1, "上传简历模式"),
    QUESTION_LIB(3, "题库抽取模式");

    public final Integer type;
    public final String value;

    InterviewModeEnum(Integer type, String value) {
        this.type = type;
        this.value = value;
    }

    public static InterviewModeEnum getByType(Integer type) {
        if (type == null) return null;
        for (InterviewModeEnum mode : values()) {
            if (mode.type.equals(type)) {
                return mode;
            }
        }
        return null;
    }
}
