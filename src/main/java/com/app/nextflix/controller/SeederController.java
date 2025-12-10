package com.app.nextflix.controller;

import com.app.nextflix.service.MovieSeederService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/seeder")
@RequiredArgsConstructor
@Slf4j
public class SeederController {

    private final MovieSeederService movieSeederService;

    @PostMapping("/seed-popular-movies")
    public ResponseEntity<Map<String, String>> seedPopularMovies() {
        log.info("Seed popular movies endpoint triggered");

        if (movieSeederService.isCurrentlySeeding()) {
            return ResponseEntity.status(409)
                    .body(Map.of(
                            "status", "SEEDING_IN_PROGRESS",
                            "message", "Seeding is already in progress. Please wait for it to complete."
                    ));
        }

        try {
            new Thread(() -> {
                try {
                    movieSeederService.seedPopularMovies();
                } catch (Exception e) {
                    log.error("Error during seeding: {}", e.getMessage(), e);
                }
            }).start();

            return ResponseEntity.ok(Map.of(
                    "status", "SEEDING_STARTED",
                    "message", "Movie seeding process started in background",
                    "estimatedDuration", "2-3 minutes for 500 movies",
                    "targetMovies", "500 popular movies",
                    "note", "Monitor logs for progress"
            ));
        } catch (Exception e) {
            log.error("Failed to start seeding: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", "SEEDING_FAILED",
                            "error", e.getMessage()
                    ));
        }
    }
}
