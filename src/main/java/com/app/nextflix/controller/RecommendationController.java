package com.app.nextflix.controller;

import com.app.nextflix.model.MovieRecommendationRequest;
import com.app.nextflix.model.MovieRecommendationResponse;
import com.app.nextflix.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class RecommendationController {

    private final RecommendationService recommendationService;

    @PostMapping
    public ResponseEntity<MovieRecommendationResponse> getRecommendations(
            @RequestBody MovieRecommendationRequest request) {

        log.info("Received recommendation request for {} movies", request.getSelectedMovies().size());

        try {
            MovieRecommendationResponse response = recommendationService.getRecommendations(request);
            log.info("Successfully processed recommendation request. Found {} recommendations",
                    response.getRecommendations().size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing recommendation request", e);
            throw e;
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "NextWatch Recommendation Engine");
        return ResponseEntity.ok(response);
    }
}
