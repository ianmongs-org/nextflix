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
public class MovieRecommendationRequest {

    private List<String> selectedMovies;

    @Builder.Default
    private int maxRecommendations = 5;
}
