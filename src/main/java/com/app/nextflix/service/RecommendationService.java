package com.app.nextflix.service;

import com.app.nextflix.model.*;
import com.app.nextflix.model.tmdb.TMDbMovieDetails;
import com.app.nextflix.monitoring.RecommendationMetrics;
import com.app.nextflix.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationService {

    private final TMDbService tmdbService;
    private final VectorSearchService vectorSearchService;
    private final MovieRepository movieRepository;
    private final ChatClient chatClient;
    private final RecommendationMetrics metrics;
    private final RecommendationQualityService qualityService;

    @Value("${tmdb.api.image-base-url}")
    private String imageBaseUrl;

    /**
     * Main recommendation engine with full observability
     */
    @Transactional
    public MovieRecommendationResponse getRecommendations(MovieRecommendationRequest request) {
        // Start tracing
        RecommendationMetrics.RecommendationTraceContext context = metrics
                .startRecommendationRequest(request.getSelectedMovies());

        try {
            long startTime = System.currentTimeMillis();
            log.info("Processing recommendation request for {} movies", request.getSelectedMovies().size());

            // Step 1: Fetch user-selected movies
            List<Movie> userMovies = fetchUserMovies(request.getSelectedMovies());

            if (userMovies.isEmpty()) {
                log.warn("No user movies found");
                metrics.recordSuccess(context, 0);
                return MovieRecommendationResponse.builder()
                        .recommendations(List.of())
                        .reasoning("No movies found for recommendations")
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Step 2: Find similar movies using vector search
            List<Movie> candidateMovies = vectorSearchService.findSimilarToMultiple(
                    userMovies,
                    request.getMaxRecommendations() * 3);

            log.info("Found {} candidate movies from vector search", candidateMovies.size());

            if (candidateMovies.isEmpty()) {
                log.warn("No candidate movies found from vector search");
                metrics.recordSuccess(context, 0);
                return MovieRecommendationResponse.builder()
                        .recommendations(List.of())
                        .reasoning("No suitable recommendations found")
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Step 3: Use LLM to curate with full logging
            List<RecommendedMovie> recommendations = curateWithAI(userMovies, candidateMovies,
                    request.getMaxRecommendations());

            // Step 4: Improve recommendation quality (diversity, rating balance, etc.)
            List<RecommendedMovie> qualityRecommendations = qualityService.improveQuality(
                    recommendations, userMovies);

            long totalLatency = System.currentTimeMillis() - startTime;

            log.info("Recommendation generation completed - Candidates: {}, Recommendations: {}, Latency: {}ms",
                    candidateMovies.size(), qualityRecommendations.size(), totalLatency);

            metrics.recordSuccess(context, qualityRecommendations.size());

            return MovieRecommendationResponse.builder()
                    .recommendations(qualityRecommendations)
                    .reasoning("Based on your taste in " + userMovies.stream()
                            .map(Movie::getTitle)
                            .collect(Collectors.joining(", ")))
                    .processingTimeMs(totalLatency)
                    .build();

        } catch (Exception e) {
            log.error("Error processing recommendation request", e);
            metrics.recordFailure(context, e);
            throw e;
        }
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
        try {
            // Use vector search scores as primary ranking
            // LLM only provides explanations, not re-ranking
            List<Movie> topCandidates = candidates.stream()
                    .limit(maxRecommendations)
                    .collect(Collectors.toList());

            String prompt = buildRefinementPrompt(userMovies, topCandidates);

            log.debug("Sending refinement prompt to LLM with structured response");
            long llmStart = System.currentTimeMillis();

            // Use .entity() for structured response parsing
            LLMRecommendationResponse llmResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(LLMRecommendationResponse.class);

            long llmLatency = System.currentTimeMillis() - llmStart;

            // Record LLM metrics
            String responseText = llmResponse.toString();
            int estimatedTokens = (prompt.length() + responseText.length()) / 4;
            metrics.recordLLMGeneration(prompt, responseText, estimatedTokens, llmLatency);

            return parseRecommendationsWithExplanations(llmResponse, topCandidates);
        } catch (Exception e) {
            log.error("LLM refinement failed, falling back to vector search results: {}", e.getMessage());
            // Fallback: return top candidates with generic explanation
            return candidates.stream()
                    .limit(maxRecommendations)
                    .map(movie -> RecommendedMovie.builder()
                            .title(movie.getTitle())
                            .overview(movie.getOverview())
                            .genres(movie.getGenres())
                            .rating(movie.getRating())
                            .posterUrl(movie.getFullPosterUrl(imageBaseUrl))
                            .trailerUrl(movie.getYoutubeEmbedUrl())
                            .whyRecommended("Similar to your taste based on vector similarity")
                            .build())
                    .collect(Collectors.toList());
        }
    }

    private String buildRefinementPrompt(List<Movie> userMovies, List<Movie> topCandidates) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a movie recommendation expert. The user loves these movies:\n\n");
        for (Movie movie : userMovies) {
            prompt.append(String.format("- %s (%s, Rating: %.1f)\n",
                    movie.getTitle(), movie.getGenres(), movie.getRating()));
        }

        prompt.append("\nWe have pre-selected these movies using vector similarity. For EACH movie below, ");
        prompt.append("provide a brief, natural explanation (1-2 sentences) of WHY it matches their taste.\n\n");
        prompt.append("IMPORTANT: Do NOT re-rank or exclude movies. Provide explanations for ALL movies.\n\n");

        for (int i = 0; i < topCandidates.size(); i++) {
            Movie movie = topCandidates.get(i);
            prompt.append(String.format("%d. %s (%s, Rating: %.1f)\n",
                    i + 1, movie.getTitle(), movie.getGenres(), movie.getRating()));
            prompt.append(String.format("   %s\n\n",
                    movie.getOverview() != null
                            ? movie.getOverview().substring(0, Math.min(200, movie.getOverview().length()))
                            : ""));
        }

        prompt.append("""
                Return a JSON response with recommendations array:
                {
                  "recommendations": [
                    {"title": "Movie 1 Title", "whyRecommended": "Explanation"},
                    {"title": "Movie 2 Title", "whyRecommended": "Explanation"}
                  ]
                }

                IMPORTANT: Return ONLY the JSON object with no other text.
                """);

        return prompt.toString();
    }

    private List<RecommendedMovie> parseRecommendationsWithExplanations(LLMRecommendationResponse llmResponse,
            List<Movie> candidates) {
        try {
            List<RecommendedMovie> recommendations = new ArrayList<>();

            if (llmResponse.getRecommendations() == null || llmResponse.getRecommendations().isEmpty()) {
                log.warn("LLM returned empty recommendations, falling back to generic explanations");
                return candidates.stream()
                        .map(movie -> RecommendedMovie.builder()
                                .title(movie.getTitle())
                                .overview(movie.getOverview())
                                .genres(movie.getGenres())
                                .rating(movie.getRating())
                                .posterUrl(movie.getFullPosterUrl(imageBaseUrl))
                                .trailerUrl(movie.getYoutubeEmbedUrl())
                                .whyRecommended("Recommended based on similarity to your taste")
                                .build())
                        .collect(Collectors.toList());
            }

            for (int i = 0; i < Math.min(llmResponse.getRecommendations().size(), candidates.size()); i++) {
                LLMRecommendationResponse.RecommendationExplanation item = llmResponse.getRecommendations().get(i);
                Movie movie = candidates.get(i);
                String explanation = item.getWhyRecommended();

                if (explanation == null || explanation.isEmpty()) {
                    explanation = "Matches your taste based on similarity analysis";
                }

                recommendations.add(RecommendedMovie.builder()
                        .title(movie.getTitle())
                        .overview(movie.getOverview())
                        .genres(movie.getGenres())
                        .rating(movie.getRating())
                        .posterUrl(movie.getFullPosterUrl(imageBaseUrl))
                        .trailerUrl(movie.getYoutubeEmbedUrl())
                        .whyRecommended(explanation)
                        .build());
            }

            return recommendations;

        } catch (Exception e) {
            log.error("Failed to parse LLM structured response, using fallback: {}", e.getMessage());
            // Return candidates with generic explanation
            return candidates.stream()
                    .map(movie -> RecommendedMovie.builder()
                            .title(movie.getTitle())
                            .overview(movie.getOverview())
                            .genres(movie.getGenres())
                            .rating(movie.getRating())
                            .posterUrl(movie.getFullPosterUrl(imageBaseUrl))
                            .trailerUrl(movie.getYoutubeEmbedUrl())
                            .whyRecommended("Recommended based on similarity to your taste")
                            .build())
                    .collect(Collectors.toList());
        }
    }
}
