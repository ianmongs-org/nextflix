package com.app.nextflix.monitoring;

import io.micrometer.core.instrument.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class RecommendationMetrics {

    private final MeterRegistry meterRegistry;
    private final Tracer recommendationTracer;
    private final Tracer llmTracer;
    private final Tracer embeddingTracer;

    // Counters
    private final Counter recommendationRequests;
    private final Counter successfulRecommendations;
    private final Counter failedRecommendations;
    private final Counter llmParseErrors;
    private final Counter vectorSearchEmpty;

    // Timers
    private final io.micrometer.core.instrument.Timer recommendationLatency;
    private final io.micrometer.core.instrument.Timer llmGenerationLatency;
    private final io.micrometer.core.instrument.Timer embeddingLatency;

    // Gauges
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger cachedVectors = new AtomicInteger(0);

    // Distribution summaries
    private final DistributionSummary candidateCount;
    private final DistributionSummary vectorSimilarityScore;
    private final DistributionSummary llmTokenUsage;

    public RecommendationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Initialize OpenTelemetry
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        // Initialize tracers
        this.recommendationTracer = tracerProvider.get("nextflix.recommendation");
        this.llmTracer = tracerProvider.get("nextflix.llm");
        this.embeddingTracer = tracerProvider.get("nextflix.embedding");

        // Initialize counters
        this.recommendationRequests = Counter.builder("recommendation.requests.total")
                .description("Total recommendation requests")
                .register(meterRegistry);

        this.successfulRecommendations = Counter.builder("recommendation.success.total")
                .description("Successful recommendations returned")
                .register(meterRegistry);

        this.failedRecommendations = Counter.builder("recommendation.failures.total")
                .description("Failed recommendation requests")
                .register(meterRegistry);

        this.llmParseErrors = Counter.builder("recommendation.llm.parse.errors")
                .description("LLM response parsing errors")
                .register(meterRegistry);

        this.vectorSearchEmpty = Counter.builder("recommendation.vector.empty.results")
                .description("Vector search returned no candidates")
                .register(meterRegistry);

        // Initialize timers
        this.recommendationLatency = io.micrometer.core.instrument.Timer.builder("recommendation.latency")
                .description("Total recommendation request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.llmGenerationLatency = io.micrometer.core.instrument.Timer.builder("llm.generation.latency")
                .description("LLM response generation latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.embeddingLatency = io.micrometer.core.instrument.Timer.builder("embedding.latency")
                .description("Embedding generation latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // Initialize gauges
        Gauge.builder("recommendation.active.requests", activeRequests::get)
                .description("Active recommendation requests")
                .register(meterRegistry);

        Gauge.builder("recommendation.cached.vectors", cachedVectors::get)
                .description("Cached vector embeddings")
                .register(meterRegistry);

        // Initialize distribution summaries
        this.candidateCount = DistributionSummary.builder("vector.candidates.count")
                .description("Number of candidate movies from vector search")
                .baseUnit("movies")
                .register(meterRegistry);

        this.vectorSimilarityScore = DistributionSummary.builder("vector.similarity.score")
                .description("Vector similarity scores")
                .scale(0.01)
                .register(meterRegistry);

        this.llmTokenUsage = DistributionSummary.builder("llm.tokens.usage")
                .description("LLM tokens used per request")
                .baseUnit("tokens")
                .register(meterRegistry);
    }

    /**
     * Record recommendation request start
     */
    public RecommendationTraceContext startRecommendationRequest(List<String> selectedMovies) {
        recommendationRequests.increment();
        activeRequests.incrementAndGet();

        Span span = recommendationTracer.spanBuilder("recommendation.request")
                .setAttribute("selected.movies.count", selectedMovies.size())
                .setAttribute("selected.movies", String.join(", ", selectedMovies))
                .startSpan();

        log.info("Recommendation request started - Movies: {}", selectedMovies);

        return new RecommendationTraceContext(span, System.currentTimeMillis());
    }

    /**
     * Record vector search execution
     */
    public void recordVectorSearch(int candidatesReturned, List<Double> similarityScores) {
        candidateCount.record(candidatesReturned);

        if (candidatesReturned == 0) {
            vectorSearchEmpty.increment();
            log.warn("Vector search returned no candidates");
        }

        if (similarityScores != null && !similarityScores.isEmpty()) {
            similarityScores.forEach(vectorSimilarityScore::record);
        }

        log.info("Vector search completed - Candidates: {}, Avg similarity: {:.4f}",
                candidatesReturned,
                similarityScores != null && !similarityScores.isEmpty()
                        ? similarityScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)
                        : 0.0);
    }

    /**
     * Record LLM generation
     */
    public void recordLLMGeneration(String userPrompt, String llmResponse, int tokensUsed, long latencyMs) {
        llmGenerationLatency.record(latencyMs, TimeUnit.MILLISECONDS);
        llmTokenUsage.record(tokensUsed);

        Span span = llmTracer.spanBuilder("llm.generation")
                .setAttribute("tokens.used", tokensUsed)
                .setAttribute("latency.ms", latencyMs)
                .setAttribute("response.length", llmResponse.length())
                .setAttribute("prompt.length", userPrompt.length())
                .startSpan();

        log.info("LLM generation completed - Tokens: {}, Latency: {}ms", tokensUsed, latencyMs);
        log.debug("LLM Prompt:\n{}", userPrompt);
        log.debug("LLM Response:\n{}", llmResponse);

        span.end();
    }

    /**
     * Record embedding generation
     */
    public void recordEmbedding(String movieTitle, long latencyMs, int dimension) {
        embeddingLatency.record(latencyMs, TimeUnit.MILLISECONDS);

        Span span = embeddingTracer.spanBuilder("embedding.generation")
                .setAttribute("movie.title", movieTitle)
                .setAttribute("latency.ms", latencyMs)
                .setAttribute("dimension", dimension)
                .startSpan();

        log.debug("Embedding generated for movie: {} ({}ms)", movieTitle, latencyMs);

        span.end();
    }

    /**
     * Record successful recommendation response
     */
    public void recordSuccess(RecommendationTraceContext context, int recommendationsReturned) {
        successfulRecommendations.increment();
        activeRequests.decrementAndGet();

        long totalLatency = System.currentTimeMillis() - context.startTime;
        recommendationLatency.record(totalLatency, TimeUnit.MILLISECONDS);

        context.span.setAttribute("recommendations.returned", recommendationsReturned);
        context.span.setAttribute("total.latency.ms", totalLatency);

        log.info("Recommendation request completed successfully - Recommendations: {}, Latency: {}ms",
                recommendationsReturned, totalLatency);

        context.span.end();
    }

    /**
     * Record failed recommendation
     */
    public void recordFailure(RecommendationTraceContext context, Exception error) {
        failedRecommendations.increment();
        activeRequests.decrementAndGet();

        context.span.recordException(error);
        context.span.setAttribute("error", true);
        context.span.setAttribute("error.message", error.getMessage());

        log.error("Recommendation request failed", error);

        context.span.end();
    }

    /**
     * Record LLM parsing error
     */
    public void recordLLMParseError(String responseText, Exception error) {
        llmParseErrors.increment();

        Span span = llmTracer.spanBuilder("llm.parse.error")
                .setAttribute("response.preview", responseText.substring(0, Math.min(100, responseText.length())))
                .setAttribute("error.message", error.getMessage())
                .startSpan();

        log.error("LLM response parsing failed: {}", error.getMessage());

        span.end();
    }

    /**
     * Get current metrics snapshot
     */
    public MetricsSnapshot getMetricsSnapshot() {
        return MetricsSnapshot.builder()
                .totalRequests((long) recommendationRequests.count())
                .successfulRequests((long) successfulRecommendations.count())
                .failedRequests((long) failedRecommendations.count())
                .activeRequests(activeRequests.get())
                .cachedVectors(cachedVectors.get())
                .avgRecommendationLatency(recommendationLatency.mean(TimeUnit.MILLISECONDS))
                .p95RecommendationLatency(getPercentile("recommendation.latency", 0.95))
                .p99RecommendationLatency(getPercentile("recommendation.latency", 0.99))
                .llmParseErrors((long) llmParseErrors.count())
                .vectorSearchEmpty((long) vectorSearchEmpty.count())
                .build();
    }

    private double getPercentile(String timerName, double percentile) {
        io.micrometer.core.instrument.Timer timer = meterRegistry.find(timerName).timer();
        if (timer != null) {
            return timer.takeSnapshot().percentileValues()[0].value(TimeUnit.MILLISECONDS);
        }
        return 0.0;
    }

    /**
     * Trace context holder
     */
    public static class RecommendationTraceContext {
        public final Span span;
        public final long startTime;

        public RecommendationTraceContext(Span span, long startTime) {
            this.span = span;
            this.startTime = startTime;
        }
    }

    /**
     * Metrics snapshot DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class MetricsSnapshot {
        private long totalRequests;
        private long successfulRequests;
        private long failedRequests;
        private int activeRequests;
        private int cachedVectors;
        private double avgRecommendationLatency;
        private double p95RecommendationLatency;
        private double p99RecommendationLatency;
        private long llmParseErrors;
        private long vectorSearchEmpty;

        public double successRate() {
            return totalRequests > 0 ? (successfulRequests * 100.0 / totalRequests) : 0.0;
        }
    }
}
