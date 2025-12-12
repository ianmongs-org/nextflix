package com.app.nextflix.service;

import com.app.nextflix.model.Movie;
import com.app.nextflix.model.tmdb.TMDbMovieDetails;
import com.app.nextflix.model.tmdb.TMDbMovie;
import com.app.nextflix.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class MovieSeederService {

    private final TMDbService tmdbService;
    private final EmbeddingService embeddingService;
    private final MovieRepository movieRepository;

    private static final int DEFAULT_MAX_MOVIES = 2000;
    private static final int PAGES_TO_FETCH = 100; // Enough pages to get 2000 movies
    private static final double MIN_RATING = 6.0;
    private static final int API_FETCH_THREADS = 10;
    private static final int EMBEDDING_THREADS = 5;
    private static final long DELAY_BETWEEN_PAGE_REQUESTS = 500;
    private static final int MAX_RETRIES = 2;

    private volatile boolean isSeeding = false;

    public boolean isCurrentlySeeding() {
        return isSeeding;
    }

    public void seedPopularMovies() {
        seedPopularMovies(DEFAULT_MAX_MOVIES);
    }

    public void seedPopularMovies(int maxMovies) {
        if (isSeeding) {
            log.warn("Seeding already in progress");
            return;
        }

        isSeeding = true;
        try {
            executeSeedingProcess(maxMovies);
        } finally {
            isSeeding = false;
        }
    }

    private void executeSeedingProcess(int maxMovies) {
        log.info("Starting movie seeding process. Target: {} movies", maxMovies);

        long startTime = System.currentTimeMillis();
        int totalFetched = 0;
        int totalSkipped = 0;

        ExecutorService apiFetchExecutor = Executors.newFixedThreadPool(API_FETCH_THREADS);
        ExecutorService embeddingExecutor = Executors.newFixedThreadPool(EMBEDDING_THREADS);
        BlockingQueue<MovieWithRetries> embeddingQueue = new LinkedBlockingQueue<>(1000);

        // Start embedding thread pool that processes queue
        Thread embeddingWorker = startEmbeddingWorker(embeddingExecutor, embeddingQueue);

        try {
            for (int page = 1; page <= PAGES_TO_FETCH; page++) {
                if (totalFetched >= maxMovies) {
                    log.info("Reached target of {} movies", maxMovies);
                    break;
                }

                try {
                    log.info("Fetching page {} of {}...", page, PAGES_TO_FETCH);

                    var tmdbMovies = tmdbService.getPopularMovies(page);
                    if (tmdbMovies == null || tmdbMovies.isEmpty()) {
                        log.warn("No movies returned for page {}", page);
                        continue;
                    }

                    // Fetch all details in parallel (not limited per page)
                    List<TMDbMovieDetails> movieDetailsList = fetchMovieDetailsInParallel(
                            apiFetchExecutor, tmdbMovies);

                    // Filter and save to DB (separate transaction, no embeddings here)
                    List<Movie> savedMovies = new ArrayList<>();
                    for (var details : movieDetailsList) {
                        if (details == null)
                            continue;
                        if (details.getVoteAverage() == null || details.getVoteAverage() < MIN_RATING) {
                            totalSkipped++;
                            continue;
                        }
                        if (movieRepository.findByTmdbId(details.getId()).isPresent()) {
                            totalSkipped++;
                            continue;
                        }

                        try {
                            Movie movie = tmdbService.convertToMovie(details);
                            Movie saved = saveMovieOnly(movie); // Save WITHOUT embedding
                            savedMovies.add(saved);
                            totalFetched++;
                        } catch (Exception e) {
                            log.warn("Failed to convert/save movie {}: {}", details.getTitle(), e.getMessage());
                            totalSkipped++;
                        }
                    }

                    // Queue movies for async embedding (non-blocking)
                    for (Movie movie : savedMovies) {
                        try {
                            embeddingQueue.offer(new MovieWithRetries(movie, 0), 100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            log.warn("Interrupted while queuing movie for embedding: {}", movie.getTitle());
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (totalFetched % 100 == 0) {
                        log.info("Progress: {}/{} movies processed (queued for embedding)", totalFetched, maxMovies);
                    }

                    try {
                        Thread.sleep(DELAY_BETWEEN_PAGE_REQUESTS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Seeding interrupted");
                        break;
                    }

                } catch (Exception e) {
                    log.error("Error processing page {}: {}", page, e.getMessage(), e);
                    totalSkipped += 20;
                }
            }

            // Signal embedding worker to finish and wait
            log.info("All movies fetched and saved to DB. Waiting for embeddings to complete...");
            embeddingQueue.offer(new MovieWithRetries(null, -1), 1, TimeUnit.SECONDS); // Poison pill

            embeddingWorker.join(5 * 60 * 1000); // Wait max 5 minutes
            if (embeddingWorker.isAlive()) {
                log.warn("Embedding worker did not complete in time");
            }

            long duration = System.currentTimeMillis() - startTime;
            logStatistics(totalFetched, totalSkipped, duration);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Seeding process interrupted", e);
        } finally {
            // Properly shutdown executors
            shutdownExecutor(apiFetchExecutor, "API Fetch");
            shutdownExecutor(embeddingExecutor, "Embedding");
        }
    }

    private Thread startEmbeddingWorker(ExecutorService executor, BlockingQueue<MovieWithRetries> queue) {
        Thread worker = new Thread(() -> {
            List<Future<?>> futures = new ArrayList<>();

            while (true) {
                try {
                    MovieWithRetries item = queue.take();

                    // Poison pill
                    if (item.movie == null && item.retries == -1) {
                        log.info("Embedding queue closed, waiting for pending embeddings...");

                        // Wait for all pending embeddings
                        for (Future<?> future : futures) {
                            try {
                                future.get(30, TimeUnit.SECONDS);
                            } catch (TimeoutException e) {
                                log.warn("Embedding task timeout");
                                future.cancel(true);
                            } catch (Exception e) {
                                log.error("Error waiting for embedding", e);
                            }
                        }
                        break;
                    }

                    // Submit embedding task
                    Future<?> future = executor.submit(() -> embedMovieWithRetry(item.movie, item.retries));
                    futures.add(future);

                    // Clean up completed futures to prevent memory leak
                    futures.removeIf(Future::isDone);

                } catch (InterruptedException e) {
                    log.warn("Embedding worker interrupted");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        worker.setName("MovieEmbeddingWorker");
        worker.start();
        return worker;
    }

    private void embedMovieWithRetry(Movie movie, int attempt) {
        try {
            embeddingService.storeMovieEmbedding(movie);
            log.debug("Embedded movie: {}", movie.getTitle());
        } catch (Exception e) {
            if (attempt < MAX_RETRIES) {
                log.warn("Failed to embed movie {} (attempt {}), retrying...", movie.getTitle(), attempt + 1);
                try {
                    Thread.sleep(1000 * (attempt + 1)); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                embedMovieWithRetry(movie, attempt + 1);
            } else {
                log.error("Failed to embed movie {} after {} attempts: {}",
                        movie.getTitle(), MAX_RETRIES + 1, e.getMessage());
            }
        }
    }

    private List<TMDbMovieDetails> fetchMovieDetailsInParallel(
            ExecutorService executor,
            List<TMDbMovie> tmdbMovies) {

        List<Future<TMDbMovieDetails>> futures = new ArrayList<>();
        List<TMDbMovieDetails> results = Collections.synchronizedList(new ArrayList<>());

        for (var movie : tmdbMovies) {
            futures.add(executor.submit(() -> {
                try {
                    return tmdbService.getMovieDetails(movie.getId());
                } catch (Exception e) {
                    log.warn("Failed to fetch details for movie {}: {}", movie.getId(), e.getMessage());
                    return null;
                }
            }));
        }

        for (var future : futures) {
            try {
                TMDbMovieDetails details = future.get(15, TimeUnit.SECONDS);
                if (details != null) {
                    results.add(details);
                }
            } catch (TimeoutException e) {
                log.warn("Timeout fetching movie details");
                future.cancel(true);
            } catch (Exception e) {
                log.warn("Error retrieving movie details: {}", e.getMessage());
            }
        }

        return results;
    }

    @Transactional
    private Movie saveMovieOnly(Movie movie) {
        // Save to DB WITHOUT embedding (no long transaction)
        return movieRepository.save(movie);
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
                log.warn("{} executor did not terminate in time, force shutting down", name);
                List<Runnable> remaining = executor.shutdownNow();
                log.warn("Cancelled {} tasks in {} executor", remaining.size(), name);
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted waiting for {} executor", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void logStatistics(int totalFetched, int totalSkipped, long duration) {
        log.info("Seeding complete!");
        log.info("Statistics:");
        log.info("   - Movies added: {}", totalFetched);
        log.info("   - Movies skipped: {}", totalSkipped);
        log.info("   - Duration: {} seconds ({} minutes)", duration / 1000, duration / 60000);
        if (totalFetched > 0) {
            log.info("   - Rate: {:.2f} movies/second", totalFetched * 1000.0 / duration);
        }
    }

    private static class MovieWithRetries {
        Movie movie;
        int retries;

        MovieWithRetries(Movie movie, int retries) {
            this.movie = movie;
            this.retries = retries;
        }
    }
}
