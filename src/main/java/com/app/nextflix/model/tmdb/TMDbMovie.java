package com.app.nextflix.model.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TMDbMovie {

    private Integer id;
    private String title;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("release_date")
    private String releaseDate;

    private Double popularity;

    @JsonProperty("vote_average")
    private Double voteAverage;

    private String overview;
}
