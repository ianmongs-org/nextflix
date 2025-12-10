package com.app.nextflix.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMRecommendationResponse {

    @JsonProperty("recommendations")
    private List<RecommendationExplanation> recommendations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecommendationExplanation {
        @JsonProperty("title")
        private String title;

        @JsonProperty("whyRecommended")
        private String whyRecommended;
    }
}
