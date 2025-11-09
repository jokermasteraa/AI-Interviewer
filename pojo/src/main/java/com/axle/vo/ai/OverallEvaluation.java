package com.axle.vo.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 综合评语
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverallEvaluation {

    /**
     * 一句话综合总评
     */
    @JsonProperty("summary")
    private String summary;

    /**
     * 亮点列表
     */
    @JsonProperty("highlights")
    private List<String> highlights;

    /**
     * 不足列表
     */
    @JsonProperty("weaknesses")
    private List<String> weaknesses;
}