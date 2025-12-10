package com.app.nextflix.service;

import com.app.nextflix.model.Movie;
import com.app.nextflix.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class VectorSearchService {

        private final VectorStore vectorStore;
        private final MovieRepository movieRepository;
        private static final int DIVERSITY_SAMPLE_SIZE = 50;

        /**
         * Find similar movies to a single movie
         */
        public List<Movie> findSimilarMovies(Movie query, int limit) {
                log.info("Searching for {} similar movies to: {}", limit, query.getTitle());

                String queryText = buildMovieQuery(query);
                var results = vectorStore.similaritySearch(
                                SearchRequest.builder()
                                                .query(queryText)
                                                .topK(limit * 2) // Fetch more for diversity filtering
                                                .build());

                return results.stream()
                                .map(doc -> {
                                        Object movieIdObj = doc.getMetadata().get("movieId");
                                        if (movieIdObj instanceof Long) {
                                                return (Long) movieIdObj;
                                        } else if (movieIdObj instanceof Integer) {
                                                return ((Integer) movieIdObj).longValue();
                                        }
                                        return null;
                                })
                                .filter(Objects::nonNull)
                                .map(movieRepository::findById)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .filter(m -> !m.getId().equals(query.getId()))
                                .limit(limit)
                                .collect(Collectors.toList());
        }

        /**
         * Find similar movies to multiple user selections using Maximal Marginal
         * Relevance (MMR)
         * This balances similarity with diversity to avoid homogeneous results
         */
        public List<Movie> findSimilarToMultiple(List<Movie> userMovies, int limit) {
                log.info("Finding {} movies similar to {} user selections using MMR", limit, userMovies.size());

                if (userMovies.isEmpty()) {
                        log.warn("No user movies provided, returning empty list");
                        return Collections.emptyList();
                }

                // Build combined query from all user movies
                String combinedQuery = buildCombinedQuery(userMovies);

                // Fetch more candidates than needed for diversity filtering
                var similarityResults = vectorStore.similaritySearch(
                                SearchRequest.builder()
                                                .query(combinedQuery)
                                                .topK(Math.min(DIVERSITY_SAMPLE_SIZE, limit * 3))
                                                .build());

                // Convert to movies with similarity scores
                List<MovieWithScore> candidates = new ArrayList<>();
                int index = 0;
                for (Document doc : similarityResults) {
                        Object movieIdObj = doc.getMetadata().get("movieId");
                        Long movieId = null;

                        if (movieIdObj instanceof Long) {
                                movieId = (Long) movieIdObj;
                        } else if (movieIdObj instanceof Integer) {
                                movieId = ((Integer) movieIdObj).longValue();
                        }

                        if (movieId != null) {
                                Optional<Movie> movie = movieRepository.findById(movieId);
                                if (movie.isPresent() && !userMovies.contains(movie.get())) {
                                        double score = 1.0 - (index * 0.01);
                                        candidates.add(new MovieWithScore(movie.get(), score));
                                        index++;
                                }
                        }
                }

                // Apply MMR (Maximal Marginal Relevance) for diversity
                return applyMaximalMarginalRelevance(candidates, limit);
        }

        /**
         * Maximal Marginal Relevance: Balance similarity with diversity
         * Prevents recommending very similar movies
         */
        private List<Movie> applyMaximalMarginalRelevance(List<MovieWithScore> candidates, int limit) {
                if (candidates.size() <= limit) {
                        return candidates.stream().map(m -> m.movie).collect(Collectors.toList());
                }

                List<Movie> selected = new ArrayList<>();
                List<MovieWithScore> remaining = new ArrayList<>(candidates);

                // Select first movie (highest similarity)
                if (!remaining.isEmpty()) {
                        selected.add(remaining.get(0).movie);
                        remaining.remove(0);
                }

                // Select remaining using MMR
                while (selected.size() < limit && !remaining.isEmpty()) {
                        MovieWithScore best = null;
                        double bestScore = Double.NEGATIVE_INFINITY;

                        for (MovieWithScore candidate : remaining) {
                                // MMR score = (similarity to query) - (similarity to selected results)
                                double similarity = candidate.score;
                                double diversity = 1.0 - calculateAverageSimilarity(candidate.movie, selected);
                                double mmrScore = (0.7 * similarity) + (0.3 * diversity);

                                if (mmrScore > bestScore) {
                                        bestScore = mmrScore;
                                        best = candidate;
                                }
                        }

                        if (best != null) {
                                selected.add(best.movie);
                                remaining.remove(best);
                                log.debug("Selected {} with MMR score {:.2f}", best.movie.getTitle(), bestScore);
                        } else {
                                break;
                        }
                }

                return selected;
        }

        /**
         * Calculate average similarity between a movie and a list of movies
         * Based on genres (simple heuristic, could be improved with actual embeddings)
         */
        private double calculateAverageSimilarity(Movie candidate, List<Movie> selected) {
                if (selected.isEmpty())
                        return 0.0;

                double totalSimilarity = 0.0;
                for (Movie movie : selected) {
                        totalSimilarity += calculateGenreSimilarity(candidate, movie);
                }
                return totalSimilarity / selected.size();
        }

        /**
         * Simple genre-based similarity (0.0 to 1.0)
         * Could be replaced with actual embedding distance
         */
        private double calculateGenreSimilarity(Movie m1, Movie m2) {
                if (m1.getGenres() == null || m2.getGenres() == null)
                        return 0.0;

                String[] genres1 = m1.getGenres().split(",");
                String[] genres2 = m2.getGenres().split(",");

                Set<String> set1 = new HashSet<>(Arrays.asList(genres1));
                Set<String> set2 = new HashSet<>(Arrays.asList(genres2));

                int intersection = 0;
                for (String genre : set1) {
                        if (set2.contains(genre.trim())) {
                                intersection++;
                        }
                }

                int union = set1.size() + set2.size() - intersection;
                return union == 0 ? 0.0 : (double) intersection / union;
        }

        /**
         * Build query text from a single movie
         */
        private String buildMovieQuery(Movie movie) {
                StringBuilder query = new StringBuilder();
                if (movie.getTitle() != null)
                        query.append(movie.getTitle()).append(" ");
                if (movie.getGenres() != null)
                        query.append(movie.getGenres()).append(" ");
                if (movie.getOverview() != null)
                        query.append(movie.getOverview());
                return query.toString();
        }

        /**
         * Build combined query from multiple user movies
         * Weights earlier movies more heavily to respect preference ordering
         */
        private String buildCombinedQuery(List<Movie> userMovies) {
                StringBuilder query = new StringBuilder();
                int weight = userMovies.size();

                for (Movie movie : userMovies) {
                        // Repeat movie text by weight to emphasize preference
                        String movieText = buildMovieQuery(movie);
                        for (int i = 0; i < weight; i++) {
                                query.append(movieText).append(" ");
                        }
                        weight--; // Decrease weight for subsequent movies
                }

                return query.toString();
        }

        /**
         * Helper class to track movies with their similarity scores
         */
        private static class MovieWithScore {
                Movie movie;
                double score;

                MovieWithScore(Movie movie, double score) {
                        this.movie = movie;
                        this.score = score;
                }
        }
}
