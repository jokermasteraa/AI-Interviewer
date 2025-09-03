package com.axle.mapper;

import com.axle.pojo.Candidate;
import com.axle.vo.CandidateVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 应聘者表 Mapper 接口
 * </p>
 *
 * @author axle
 * @since 2025-08-29
 */
public interface CandidateMapperCustom extends BaseMapper<Candidate> {
    public List<CandidateVO> queryAllcandidate(@Param("ParamMap")  Map<String, Object> map);
}
