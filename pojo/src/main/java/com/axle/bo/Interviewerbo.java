package com.axle.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 数字人面试官表
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Data
public class Interviewerbo implements Serializable {

    //private static final long serialVersionUID = 1L;

    private String id;

    /**
     * 数字人面试官的名称
     */
    @NotBlank(message = "数字人面试官的名字不能为空")
    private String aiName;

    /**
     * 数字人形象图照片
     */
    @NotBlank(message = "数字人面试官的形象图不能为空")
    private String image;

}
