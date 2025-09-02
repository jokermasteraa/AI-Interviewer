package com.axle.bo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.j2objc.annotations.Property;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
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
public class JobBO{

    private String id;
    private String jobName;
    private String jobDesc;
    private Integer status;
    private String interviewerId;
    private String prompt;

}
