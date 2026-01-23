package com.axle.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class SubmitAnswerBO {

    private String candidateId;
    private String jobId;
    private List<AnswerBO> questionAnswerList;
    private Integer totalSeconds;
    /**
     * 面试模式：1-上传简历模式，3-题库抽取模式
     */
    private Integer interviewMode;
}
