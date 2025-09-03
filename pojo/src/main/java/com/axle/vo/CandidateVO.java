package com.axle.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 应聘者表
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class CandidateVO  {

    private String candidateId;
    private String realName;
    private String identityNum;
    private String mobile;
    private Integer sex;
    private String email;
    private LocalDate birthday;
    private String jobId;
    private String jobName;
    private LocalDateTime createTime;
}
