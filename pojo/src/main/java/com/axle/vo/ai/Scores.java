package com.axle.vo.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 维度评分
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Scores {

    /**
     * 技术能力 (1-100)
     */
    @JsonProperty("technical_ability")
    private int technicalAbility;

    /**
     * 项目经验 (1-100)
     */
    @JsonProperty("project_experience")
    private int projectExperience;

    /**
     * 逻辑思维 (1-100)
     */
    @JsonProperty("logical_thinking")
    private int logicalThinking;
}