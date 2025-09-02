package com.axle.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * <p>
 * 职位信息表
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */
@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class JobVO {

    private String jobId;
    private String jobName;
    private String jobDesc;
    private Integer status;
    private String interviewerId;
    private String prompt;
    private String interviewerName;
    private LocalDateTime createTime;
    private LocalDateTime updatedTime;

}
