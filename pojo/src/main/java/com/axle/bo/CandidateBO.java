package com.axle.bo;

import com.fasterxml.jackson.annotation.JsonProperty;
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
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class CandidateBO  {

    private String id;
    private String realName;
    private String identityNum;
    private Integer sex;
    private String mobile;
    private String email;
    private LocalDate birthday;
    private String jobId;

}
