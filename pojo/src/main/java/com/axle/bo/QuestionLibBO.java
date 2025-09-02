package com.axle.bo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class QuestionLibBO {

    //private static final long serialVersionUID = 1L;
    @JsonProperty("questionId")
    private String id;

    /**
     * 面试题（文字内容）
     */
    private String question;

    /**
     * 参考答案
     */
    private String referenceAnswer;

    /**
     * 面试数字人对应的地址
     */
    private String aiSrc;

    /**
     * 分配的数字人面试官id，每个职位需要对应的面试官来进行面试
     */
    private String interviewerId;

    /**
     * 1：启用本题
     0：关闭本题
     */
    private Integer isOn;

}
