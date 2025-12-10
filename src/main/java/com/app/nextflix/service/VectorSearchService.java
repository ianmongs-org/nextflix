package com.app.nextflix.service;

import com.app.nextflix.model.Movie;
import com.app.nextflix.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class VectorSearchService {

        private final VectorStore vectorStore;
        private final MovieRepository movieRepository;

        public List<Movie> findSimilarMovies(String query, int limit) {
                log.info("Searching for {} similar movies", limit);
                var results = vectorStore.similaritySearch(
                                SearchRequest.builder()
                                                .query(query)
                                                .topK(limit)
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
                                .filter(java.util.Objects::nonNull)
                                .map(movieRepository::findById)
                                .filter(java.util.Optional::isPresent)
                                .map(java.util.Optional::get)
                                .toList();
        }

        public List<Movie> findSimilarToMultiple(List<Movie> userMovies, int limit) {
                log.info("Finding movies similar to {} user selections", userMovies.size());
                String query = userMovies.stream()
                                .map(m -> m.getTitle() + " " + m.getGenres())
                                .reduce((a, b) -> a + " " + b)
                                .orElse("");

                List<Movie> similar = findSimilarMovies(query, limit * 2);

                return similar.stream()
                                .filter(m -> !userMovies.contains(m))
                                .limit(limit)
                                .toList();
        }
}
