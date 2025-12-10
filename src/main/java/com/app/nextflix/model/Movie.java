package com.app.nextflix.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "movies")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_id", unique = true, nullable = false)
    private Integer tmdbId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String overview;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(length = 500)
    private String genres;

    private Double rating;

    @Column(name = "poster_path", length = 500)
    private String posterPath;

    @Column(name = "youtube_key", length = 100)
    private String youtubeKey;

    private Double popularity;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getFullPosterUrl(String baseUrl) {
        return posterPath != null ? baseUrl + posterPath : null;
    }

    public String getYoutubeEmbedUrl() {
        return youtubeKey != null ? "https://youtube.com/embed/" + youtubeKey : null;
    }
}
