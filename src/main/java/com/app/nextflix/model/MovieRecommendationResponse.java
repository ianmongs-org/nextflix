package com.app.nextflix.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieRecommendationResponse {

    private List<RecommendedMovie> recommendations;
    private String reasoning;
    private long processingTimeMs;
}
