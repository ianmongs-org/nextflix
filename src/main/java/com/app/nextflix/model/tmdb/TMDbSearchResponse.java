package com.app.nextflix.model.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TMDbSearchResponse {

    private List<TMDbMovie> results;
    private int page;

    @com.fasterxml.jackson.annotation.JsonProperty("total_pages")
    private int totalPages;

    @com.fasterxml.jackson.annotation.JsonProperty("total_results")
    private int totalResults;
}
