package com.axle.mapper;

import com.axle.vo.JobVO;
import org.apache.ibatis.annotations.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface JobMapperCustom {
    public List<JobVO>  queryAllJobList (@Param("ParamMap") Map<String,Object> map);
    public List<HashMap<String,Object>>  queryNameList (@Param("ParamMap") Map<String,Object> map);
}
