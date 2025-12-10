package com.app.nextflix.service;

import com.app.nextflix.model.Movie;
import com.app.nextflix.model.tmdb.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TMDbService {

    private final WebClient webClient;
    private final String apiKey;
    private final String imageBaseUrl;

    public TMDbService(
            @Value("${tmdb.api.key}") String apiKey,
            @Value("${tmdb.api.base-url}") String baseUrl,
            @Value("${tmdb.api.image-base-url}") String imageBaseUrl) {
        this.apiKey = apiKey;
        this.imageBaseUrl = imageBaseUrl;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public List<TMDbMovie> searchMovies(String query) {
        log.info("Searching TMDb for: {}", query);

        TMDbSearchResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/movie")
                        .queryParam("query", query)
                        .queryParam("include_adult", false)
                        .build())
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(TMDbSearchResponse.class)
                .block();

        return response != null ? response.getResults() : List.of();
    }

    public List<TMDbMovie> getPopularMovies(int page) {
        log.debug("Fetching popular movies from TMDb, page: {}", page);

        TMDbSearchResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/popular")
                        .queryParam("page", page)
                        .queryParam("sort_by", "popularity.desc")
                        .build())
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(TMDbSearchResponse.class)
                .block();

        return response != null ? response.getResults() : List.of();
    }

    public TMDbMovieDetails getMovieDetails(Integer tmdbId) {
        log.info("Fetching movie details for TMDb ID: {}", tmdbId);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/{id}")
                        .build(tmdbId))
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(TMDbMovieDetails.class)
                .block();
    }

    public String getMovieTrailer(Integer tmdbId) {
        log.info("Fetching trailer for TMDb ID: {}", tmdbId);

        try {
            TMDbVideosResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/movie/{id}/videos")
                            .build(tmdbId))
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .bodyToMono(TMDbVideosResponse.class)
                    .block();

            if (response != null && response.getResults() != null) {
                return response.getResults().stream()
                        .filter(v -> "YouTube".equals(v.getSite()) && "Trailer".equals(v.getType()))
                        .findFirst()
                        .map(Video::getKey)
                        .orElse(null);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch trailer for TMDb ID {}: {}", tmdbId, e.getMessage());
        }

        return null;
    }

    public Movie convertToMovie(TMDbMovieDetails details) {
        String genres = details.getGenres() != null ? details.getGenres().stream()
                .map(Genre::getName)
                .collect(Collectors.joining(", ")) : "";

        String trailerKey = getMovieTrailer(details.getId());

        return Movie.builder()
                .tmdbId(details.getId())
                .title(details.getTitle())
                .overview(details.getOverview())
                .releaseDate(parseDate(details.getReleaseDate()))
                .genres(genres)
                .rating(details.getVoteAverage())
                .posterPath(details.getPosterPath())
                .youtubeKey(trailerKey)
                .popularity(details.getPopularity())
                .build();
    }

    public String getFullPosterUrl(String posterPath) {
        return posterPath != null ? imageBaseUrl + posterPath : null;
    }

    private LocalDate parseDate(String dateString) {
        try {
            return dateString != null && !dateString.isEmpty() ? LocalDate.parse(dateString) : null;
        } catch (Exception e) {
            log.warn("Failed to parse date: {}", dateString);
            return null;
        }
    }
}
