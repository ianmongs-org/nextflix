package com.app.nextflix.controller;

import com.app.nextflix.monitoring.RecommendationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes monitoring metrics and health information
 */
@RestController
@RequestMapping("/api/metrics")
@Slf4j
@RequiredArgsConstructor
public class MetricsController {

    private final RecommendationMetrics recommendationMetrics;

    /**
     * Get current metrics snapshot
     * GET /api/metrics/recommendation
     */
    @GetMapping("/recommendation")
    public ResponseEntity<RecommendationMetrics.MetricsSnapshot> getRecommendationMetrics() {
        RecommendationMetrics.MetricsSnapshot snapshot = recommendationMetrics.getMetricsSnapshot();
        return ResponseEntity.ok(snapshot);
    }

    /**
     * Example response:
     * {
     * "totalRequests": 42,
     * "successfulRequests": 40,
     * "failedRequests": 2,
     * "activeRequests": 0,
     * "cachedVectors": 487,
     * "avgRecommendationLatency": 3250.5,
     * "p95RecommendationLatency": 5120.3,
     * "p99RecommendationLatency": 6890.2,
     * "llmParseErrors": 1,
     * "vectorSearchEmpty": 0,
     * "successRate": 95.24
     * }
     */
}
