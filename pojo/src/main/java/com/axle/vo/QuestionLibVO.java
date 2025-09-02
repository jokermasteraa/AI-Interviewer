package com.axle.vo;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 面试题库表（每个数字人面试官都会对应一些面试题）
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class QuestionLibVO  {

    private String questionId;
    private String question;
    private String referenceAnswer;
    private String aiSrc;
    private String interviewerId;
    private Integer isOn;
    private String interviewerName;
    private LocalDateTime createTime;
    private LocalDateTime updatedTime;




}
