-- Enable pgvector extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Movies table (metadata only - embeddings stored in vector_store)
CREATE TABLE IF NOT EXISTS movies (
    id BIGSERIAL PRIMARY KEY,
    tmdb_id INTEGER UNIQUE NOT NULL,
    title VARCHAR(500) NOT NULL,
    overview TEXT,
    release_date DATE,
    genres VARCHAR(500),
    rating DECIMAL(3,1),
    poster_path VARCHAR(500),
    youtube_key VARCHAR(100),
    popularity DECIMAL(10,3),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS movies_tmdb_id_idx ON movies(tmdb_id);
CREATE INDEX IF NOT EXISTS movies_title_idx ON movies(title);
CREATE INDEX IF NOT EXISTS movies_rating_idx ON movies(rating DESC);