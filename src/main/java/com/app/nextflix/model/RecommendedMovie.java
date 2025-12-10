package com.app.nextflix.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedMovie {

    private String title;
    private String overview;
    private String genres;
    private Double rating;
    private String posterUrl;
    private String trailerUrl;
    private String whyRecommended;
}
