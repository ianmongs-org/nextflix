package com.app.nextflix.service;

import com.app.nextflix.model.Movie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    public void storeMovieEmbedding(Movie movie) {
        String content = buildMovieText(movie);
        Document doc = new Document(content, Map.of(
                "movieId", movie.getId(),
                "title", movie.getTitle(),
                "tmdbId", movie.getTmdbId()));
        vectorStore.add(List.of(doc));
        log.info("Stored embedding for movie: {}", movie.getTitle());
    }

    private String buildMovieText(Movie movie) {
        StringBuilder text = new StringBuilder();

        text.append("Title: ").append(movie.getTitle()).append("\n");

        if (movie.getReleaseDate() != null) {
            text.append("Release Date: ").append(movie.getReleaseDate()).append("\n");
        }

        if (movie.getGenres() != null && !movie.getGenres().isEmpty()) {
            text.append("Genres: ").append(movie.getGenres()).append("\n");
        }

        if (movie.getRating() != null) {
            text.append("Rating: ").append(String.format("%.1f/10", movie.getRating())).append("\n");
        }

        if (movie.getPopularity() != null) {
            text.append("Popularity: ").append(String.format("%.1f", movie.getPopularity())).append("\n");
        }

        if (movie.getOverview() != null && !movie.getOverview().isEmpty()) {
            text.append("Overview: ").append(movie.getOverview()).append("\n");
        }

        return text.toString();
    }
}
