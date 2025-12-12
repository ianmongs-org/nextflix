package com.app.nextflix.scheduler;

import com.app.nextflix.service.MovieSeederService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Scheduled jobs for movie seeding
 * - Initial seed on startup: 2000 high-quality movies (one-time)
 * - Weekly refresh: Fetch latest/trending movies (every Monday)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MovieSeederScheduler {

    private final MovieSeederService movieSeederService;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int INITIAL_SEED_COUNT = 2000;
    private static final int WEEKLY_REFRESH_COUNT = 500; // Fetch latest 500 movies

    /**
     * Initial seed on application startup (2000 movies)
     * Runs 30 seconds after app starts to allow dependencies to initialize
     */
    @Scheduled(initialDelay = 30000, fixedDelay = Long.MAX_VALUE)
    public void seedMoviesOnStartup() {
        log.info("Initializing movie database on startup");
        log.info("========================================");
        log.info("Starting initial movie catalog seed at {}", LocalDateTime.now().format(formatter));
        log.info("Target: {} high-quality movies", INITIAL_SEED_COUNT);
        log.info("========================================");

        try {
            long startTime = System.currentTimeMillis();
            movieSeederService.seedPopularMovies(INITIAL_SEED_COUNT);
            long duration = System.currentTimeMillis() - startTime;

            log.info("Initial catalog complete in {}ms ({} minutes)", duration, duration / 60000);
            log.info("Next refresh scheduled weekly on Monday at midnight");

        } catch (Exception e) {
            log.warn("Initial seeding failed (non-blocking): {}", e.getMessage());
            // Don't fail startup if seeding fails
        }
    }

    /**
     * Weekly refresh job (Every Monday at midnight)
     * Fetches latest trending movies to keep the catalog fresh
     */
    @Scheduled(cron = "0 0 0 * * MON") // Monday midnight
    public void refreshLatestMoviesWeekly() {
        log.info("========================================");
        log.info("Starting weekly movie catalog refresh at {}", LocalDateTime.now().format(formatter));
        log.info("Fetching latest {} trending movies", WEEKLY_REFRESH_COUNT);
        log.info("========================================");

        try {
            long startTime = System.currentTimeMillis();
            movieSeederService.seedPopularMovies(WEEKLY_REFRESH_COUNT);
            long duration = System.currentTimeMillis() - startTime;

            log.info("Weekly refresh completed in {}ms ({} minutes)", duration, duration / 60000);
            log.info("Next refresh scheduled for next Monday");

        } catch (Exception e) {
            log.error("Weekly refresh failed", e);
            // Continue running - don't crash the app if refresh fails
        }
    }
}
