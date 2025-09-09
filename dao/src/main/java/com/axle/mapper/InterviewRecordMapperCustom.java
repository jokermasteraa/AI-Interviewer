package com.axle.mapper;

import com.axle.pojo.InterviewRecord;
import com.axle.vo.InterviewRecordVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 面试记录表 Mapper 接口
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */
public interface InterviewRecordMapperCustom extends BaseMapper<InterviewRecord> {

    List<InterviewRecordVO> queryAllRecords(@Param("ParamMap") Map<String, Object> map);
}
