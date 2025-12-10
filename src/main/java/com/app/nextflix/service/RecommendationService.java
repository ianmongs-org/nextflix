package com.app.nextflix.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.app.nextflix.model.*;
import com.app.nextflix.model.tmdb.TMDbMovieDetails;
import com.app.nextflix.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationService {

    private final TMDbService tmdbService;
    private final VectorSearchService vectorSearchService;
    private final MovieRepository movieRepository;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Value("${tmdb.api.image-base-url}")
    private String imageBaseUrl;

    /**
     * Main recommendation engine
     */
    @Transactional
    public MovieRecommendationResponse getRecommendations(MovieRecommendationRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Processing recommendation request for {} movies", request.getSelectedMovies().size());

        // Step 1: Fetch user-selected movies
        List<Movie> userMovies = fetchUserMovies(request.getSelectedMovies());

        // Step 2: Find similar movies using vector search
        List<Movie> candidateMovies = vectorSearchService.findSimilarToMultiple(
                userMovies,
                request.getMaxRecommendations() * 4);

        log.info("Found {} candidate movies from vector search", candidateMovies.size());

        // Step 3: Use LLM to curate
        List<RecommendedMovie> recommendations = curateWithAI(userMovies, candidateMovies,
                request.getMaxRecommendations());

        long endTime = System.currentTimeMillis();

        return MovieRecommendationResponse.builder()
                .recommendations(recommendations)
                .reasoning("Based on your taste in " + userMovies.stream()
                        .map(Movie::getTitle)
                        .collect(Collectors.joining(", ")))
                .processingTimeMs(endTime - startTime)
                .build();
    }

    /**
     * Fetch movies from TMDb and store with embeddings
     */
    private List<Movie> fetchUserMovies(List<String> movieTitles) {
        return movieTitles.stream()
                .map(this::findOrCreateMovie)
                .collect(Collectors.toList());
    }

    /**
     * Find movie in DB or fetch from TMDb
     * NOTE: Does NOT create embeddings. Embeddings are only created during seeding.
     * User-selected movies are just reference points for vector similarity search.
     */
    private Movie findOrCreateMovie(String title) {
        // First check local DB (exact match)
        var existing = movieRepository.findByTitleIgnoreCase(title);

        if (existing.isPresent()) {
            return existing.get();
        }

        // Search TMDb if not in DB
        List<com.app.nextflix.model.tmdb.TMDbMovie> searchResults = tmdbService.searchMovies(title);

        if (searchResults.isEmpty()) {
            throw new RuntimeException("Movie not found: " + title);
        }

        // Get full details
        TMDbMovieDetails details = tmdbService.getMovieDetails(searchResults.get(0).getId());

        // Check if movie already exists by TMDb ID (race condition handling)
        var existingByTmdbId = movieRepository.findByTmdbId(details.getId());
        if (existingByTmdbId.isPresent()) {
            return existingByTmdbId.get();
        }

        Movie movie = tmdbService.convertToMovie(details);

        // Save to DB but DO NOT create embedding
        // Embeddings are only created during seeding phase
        try {
            Movie saved = movieRepository.save(movie);
            log.info("Fetched and saved user-selected movie: {} (no embedding created)", saved.getTitle());
            return saved;
        } catch (Exception e) {
            // If duplicate key error, fetch the existing movie
            log.warn("Duplicate movie detected (TMDb ID: {}), fetching existing", details.getId());
            var retry = movieRepository.findByTmdbId(details.getId());
            if (retry.isPresent()) {
                return retry.get();
            }
            throw e;
        }
    }

    private List<RecommendedMovie> curateWithAI(List<Movie> userMovies, List<Movie> candidates,
            int maxRecommendations) {
        String prompt = buildRecommendationPrompt(userMovies, candidates, maxRecommendations);

        log.debug("Sending prompt to LLM:\n{}", prompt);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        log.debug("Received LLM response:\n{}", response);

        return parseAndEnrichRecommendations(response, candidates);
    }

    private String buildRecommendationPrompt(List<Movie> userMovies, List<Movie> candidates, int maxRecommendations) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a movie recommendation expert. Analyze these movies the user loves:\n\n");

        for (Movie movie : userMovies) {
            prompt.append(String.format("- **%s** (%s)\n", movie.getTitle(), movie.getGenres()));
            prompt.append(String.format("  Rating: %.1f | Overview: %s\n\n", movie.getRating(), movie.getOverview()));
        }

        prompt.append("\nBased on vector similarity, here are candidate movies:\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            Movie movie = candidates.get(i);
            prompt.append(String.format("%d. **%s** (%s) - Rating: %.1f\n",
                    i + 1, movie.getTitle(), movie.getGenres(), movie.getRating()));
            prompt.append(String.format("   %s\n\n", movie.getOverview()));
        }

        prompt.append(String.format("""

                Your task:
                1. Select the best %d movies from the candidates
                2. Consider themes, genres, tone, storytelling style, and emotional resonance
                3. Explain WHY each movie fits the user's taste
                4. Prioritize quality (rating > 7.0) but value perfect fit over high rating

                Return ONLY a JSON array in this exact format:
                [
                  {
                    "title": "Movie Title",
                    "whyRecommended": "Brief explanation of why this fits their taste"
                  }
                ]

                NO OTHER TEXT. Just the JSON array.
                """, maxRecommendations));

        return prompt.toString();
    }

    private List<RecommendedMovie> parseAndEnrichRecommendations(String llmResponse, List<Movie> candidates) {
        try {
            String json = extractJson(llmResponse);

            @SuppressWarnings("unchecked")
            List<java.util.Map<String, String>> parsed = objectMapper.readValue(json, List.class);

            return parsed.stream()
                    .map(item -> {
                        String title = item.get("title");
                        String why = item.get("whyRecommended");

                        // Find matching movie from candidates
                        Movie movie = candidates.stream()
                                .filter(m -> m.getTitle().equalsIgnoreCase(title))
                                .findFirst()
                                .orElse(null);

                        if (movie == null) {
                            log.warn("Movie not found in candidates: {}", title);
                            return null;
                        }

                        return RecommendedMovie.builder()
                                .title(movie.getTitle())
                                .overview(movie.getOverview())
                                .genres(movie.getGenres())
                                .rating(movie.getRating())
                                .posterUrl(movie.getFullPosterUrl(imageBaseUrl))
                                .trailerUrl(movie.getYoutubeEmbedUrl())
                                .whyRecommended(why)
                                .build();
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse LLM response: {}", llmResponse, e);
            throw new RuntimeException("Failed to parse recommendations", e);
        }
    }

    /**
     * Extract JSON array from potentially messy LLM response
     */
    private String extractJson(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']') + 1;

        if (start >= 0 && end > start) {
            return response.substring(start, end);
        }

        return response;
    }
}
