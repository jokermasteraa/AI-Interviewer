package com.axle.bo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class NextQuestionResponse {

    @JsonProperty("next_question")
    private String nextQuestion;

    @JsonProperty("is_finished")
    private boolean isFinished;
}