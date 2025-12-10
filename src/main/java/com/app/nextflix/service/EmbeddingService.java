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
        return String.join(" ",
                "Title: " + movie.getTitle(),
                "Genres: " + (movie.getGenres() != null ? movie.getGenres() : ""),
                "Overview: " + (movie.getOverview() != null ? movie.getOverview() : ""));
    }
}
