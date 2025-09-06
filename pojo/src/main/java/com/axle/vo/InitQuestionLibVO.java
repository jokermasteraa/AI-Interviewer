package com.axle.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

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
public class InitQuestionLibVO {

    private String id;
    private String question;
    private String referenceAnswer;
    private String aiSrc;
    private String interviewerId;



}
