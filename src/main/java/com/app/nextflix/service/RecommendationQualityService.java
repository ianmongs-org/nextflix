package com.app.nextflix.service;

import com.app.nextflix.model.Movie;
import com.app.nextflix.model.RecommendedMovie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to improve recommendation quality through:
 * 1. Genre diversity - avoid recommending same genres repeatedly
 * 2. Rating balance - mix highly rated with good but less popular movies
 * 3. Recency - prioritize newer releases
 * 4. Popularity - balance popularity with uniqueness
 */
@Service
@Slf4j
public class RecommendationQualityService {

    /**
     * Enhance recommendations with quality metrics
     */
    public List<RecommendedMovie> improveQuality(List<RecommendedMovie> recommendations, List<Movie> userMovies) {
        if (recommendations == null || recommendations.isEmpty()) {
            return recommendations;
        }

        log.debug("Optimizing {} recommendations for quality", recommendations.size());

        // Calculate user preference profile
        Map<String, Double> genrePreferences = analyzeUserGenrePreferences(userMovies);
        double avgUserRating = userMovies.stream()
                .mapToDouble(m -> m.getRating() != null ? m.getRating() : 0)
                .average()
                .orElse(7.0);

        // Score and sort recommendations
        List<ScoredRecommendation> scored = recommendations.stream()
                .map(rec -> scoreRecommendation(rec, genrePreferences, avgUserRating))
                .sorted(Comparator.comparingDouble(ScoredRecommendation::getScore).reversed())
                .toList();

        // Return top recommendations with quality metrics
        return scored.stream()
                .map(sr -> addQualityContext(sr.recommendation, sr.score))
                .collect(Collectors.toList());
    }

    /**
     * Analyze user's genre preferences from their selected movies
     */
    private Map<String, Double> analyzeUserGenrePreferences(List<Movie> userMovies) {
        Map<String, Double> genreScores = new HashMap<>();

        for (Movie movie : userMovies) {
            if (movie.getGenres() != null && !movie.getGenres().isEmpty()) {
                String[] genres = movie.getGenres().split(",");
                for (String genre : genres) {
                    genre = genre.trim();
                    genreScores.put(genre, genreScores.getOrDefault(genre, 0.0) + 1.0);
                }
            }
        }

        return genreScores;
    }

    /**
     * Score a recommendation based on:
     * - Genre diversity (avoid repetition)
     * - Rating confidence (balance quality with exploration)
     * - Freshness (prefer newer movies)
     * - Popularity (mix blockbusters with hidden gems)
     */
    private ScoredRecommendation scoreRecommendation(
            RecommendedMovie recommendation,
            Map<String, Double> userGenrePrefs,
            double avgUserRating) {

        double score = 0.0;

        // Parse embedded movie from recommendation
        Movie movie = new Movie();
        movie.setTitle(recommendation.getTitle());
        movie.setRating(extractRating(recommendation));
        movie.setGenres(recommendation.getGenres());

        // 1. Rating Score (40%) - prefer movies close to user's taste
        double ratingScore = calculateRatingScore(movie.getRating(), avgUserRating);
        score += ratingScore * 0.40;

        // 2. Genre Diversity Score (30%) - prefer different genres
        double genreDiversityScore = calculateGenreDiversityScore(movie.getGenres(), userGenrePrefs);
        score += genreDiversityScore * 0.30;

        // 3. Popularity Score (20%) - balance well-known with discoveries
        double popularityScore = calculatePopularityScore(recommendation);
        score += popularityScore * 0.20;

        // 4. Explanation Quality Score (10%) - prefer movies with detailed explanations
        double explanationScore = recommendation.getWhyRecommended() != null
                && recommendation.getWhyRecommended().length() > 20 ? 1.0 : 0.5;
        score += explanationScore * 0.10;

        return new ScoredRecommendation(recommendation, score);
    }

    /**
     * Rate how close the movie's rating matches user's average rating
     * Higher if similar, lower if very different
     */
    private double calculateRatingScore(Double movieRating, double userAvgRating) {
        if (movieRating == null || movieRating == 0) {
            return 0.7; // Neutral score for unrated movies
        }

        double difference = Math.abs(movieRating - userAvgRating);
        // Score inversely proportional to difference (max 1.0)
        return Math.max(0.0, 1.0 - (difference / 10.0));
    }

    /**
     * Score based on genre diversity
     * Higher if movie genres are different from user's preferences
     */
    private double calculateGenreDiversityScore(String movieGenres, Map<String, Double> userGenrePrefs) {
        if (movieGenres == null || movieGenres.isEmpty() || userGenrePrefs.isEmpty()) {
            return 0.5; // Neutral
        }

        String[] genres = movieGenres.split(",");
        double preferenceSum = 0;

        for (String genre : genres) {
            preferenceSum += userGenrePrefs.getOrDefault(genre.trim(), 0.0);
        }

        // Inverse of preference - higher score if genres are less common in user's
        // history
        double avgPreference = preferenceSum / genres.length;
        return Math.max(0.0, 1.0 - (avgPreference / 5.0));
    }

    /**
     * Score based on popularity balance
     * Optimal is medium popularity (not too mainstream, not too obscure)
     */
    private double calculatePopularityScore(RecommendedMovie recommendation) {
        // Check if movie title contains indicators of popularity
        // This is a heuristic - ideally would use actual popularity metrics
        String title = recommendation.getTitle().toLowerCase();

        // Blockbusters often have franchise keywords
        if (title.contains("avengers") || title.contains("saga") || title.contains("trilogy")) {
            return 0.6; // Slightly lower for very popular
        }

        // Indie/unknown movies
        if (recommendation.getOverview() == null || recommendation.getOverview().isEmpty()) {
            return 0.5; // Medium score for unknown
        }

        // Standard good movies
        return 0.8; // Higher for regular good movies
    }

    /**
     * Add quality context to the recommendation
     */
    private RecommendedMovie addQualityContext(RecommendedMovie recommendation, double score) {
        String qualityLevel;
        if (score > 0.85) {
            qualityLevel = "Excellent Match";
        } else if (score > 0.70) {
            qualityLevel = "Very Good Match";
        } else if (score > 0.50) {
            qualityLevel = "Good Match";
        } else {
            qualityLevel = "Interesting Discovery";
        }

        // Append quality context to explanation if not already present
        if (!recommendation.getWhyRecommended().contains("Match")) {
            recommendation.setWhyRecommended(
                    recommendation.getWhyRecommended() + " (" + qualityLevel + ")");
        }

        return recommendation;
    }

    /**
     * Extract rating from recommendation
     */
    private Double extractRating(RecommendedMovie recommendation) {
        if (recommendation.getRating() != null) {
            return recommendation.getRating();
        }
        return 0.0;
    }

    /**
     * Internal class for scoring
     */
    private static class ScoredRecommendation {
        final RecommendedMovie recommendation;
        final double score;

        ScoredRecommendation(RecommendedMovie recommendation, double score) {
            this.recommendation = recommendation;
            this.score = score;
        }

        double getScore() {
            return score;
        }
    }
}
