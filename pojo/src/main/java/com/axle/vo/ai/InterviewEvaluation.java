package com.axle.vo.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 评估的结构化输出 (根对象)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InterviewEvaluation {

    /**
     * 维度评分
     */
    @JsonProperty("scores")
    private Scores scores;

    /**
     * 综合评语
     */
    @JsonProperty("overall_evaluation")
    private OverallEvaluation overallEvaluation;
}