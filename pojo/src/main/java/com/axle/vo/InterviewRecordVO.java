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
 * 面试记录表
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */

@ToString
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InterviewRecordVO  {


    private String id;
    private String realName;
    private String identityNum;
    private String sex;
    private String mobile;
    private String jobName;
    private Integer takeTime;
    private String result;
    private LocalDateTime createTime;

}
