package com.app.nextflix.repository;

import com.app.nextflix.model.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findByTmdbId(Integer tmdbId);

    Optional<Movie> findByTitleIgnoreCase(String title);
}
