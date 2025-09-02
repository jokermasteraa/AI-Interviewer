package com.axle.mapper;

import com.axle.pojo.QuestionLib;
import com.axle.vo.QuestionLibVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 面试题库表（每个数字人面试官都会对应一些面试题） Mapper 接口
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */
public interface QuestionLibCustomMapper  {
     List<QuestionLibVO> queryQuestionLibList(@Param("ParamMap") Map<String,Object> map);
}
