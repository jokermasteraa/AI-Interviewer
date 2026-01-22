package com.axle.pojo;

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

@AllArgsConstructor
@NoArgsConstructor
@ToString
@Data
public class QuestionId  {

    private String id;

}
