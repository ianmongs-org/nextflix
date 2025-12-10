# NextWatch — Movie Recommendation Engine

A Spring Boot backend for AI-powered movie recommendations using semantic embeddings, vector search, and LLM curation.

## Quick Start

```bash
# 1. Setup database
createdb nextflix
psql -d nextflix -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2. Set environment
export TMDB_API_KEY=your_key
export AZURE_OPENAI_API_KEY=your_key
export AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/

# 3. Build & run
mvn clean install
mvn spring-boot:run
```

## Architecture

**Flow**: User selects 3-4 movies → fetch metadata from TMDb → generate embeddings → vector similarity search in PostgreSQL → LLM curates top matches → return JSON with title, rating, overview, genres, poster, trailer.

**Tech Stack**:

- Java 25, Spring Boot 3.5.8
- PostgreSQL + pgvector (1536-dim vectors)
- Azure OpenAI (embeddings + GPT-4)
- TMDb API (movie metadata)

## Project Structure

```
src/main/java/com/app/nextflix/
├── NextflixApplication.java
├── config/
│   ├── AppConfig.java
│   └── AzureAiConfig.java
├── controller/
│   └── RecommendationController.java
├── model/
│   ├── Movie.java
│   ├── MovieRecommendationRequest.java
│   ├── MovieRecommendationResponse.java
│   ├── RecommendedMovie.java
│   └── tmdb/ (6 DTO classes)
├── repository/
│   └── MovieRepository.java
└── service/
    ├── TMDbService.java
    ├── EmbeddingService.java
    ├── VectorSearchService.java
    └── RecommendationService.java
```

## API Endpoint

```bash
POST /api/recommendations
Content-Type: application/json

{
  "selectedMovies": ["Inception", "Interstellar", "The Prestige"],
  "maxRecommendations": 5
}
```

Response:

```json
{
  "recommendations": [
    {
      "title": "Tenet",
      "overview": "...",
      "genres": "Action, Sci-Fi",
      "rating": 7.3,
      "posterUrl": "https://image.tmdb.org/...",
      "trailerUrl": "https://youtube.com/embed/...",
      "whyRecommended": "Complex sci-fi with mind-bending themes"
    }
  ],
  "reasoning": "Based on your taste in...",
  "processingTimeMs": 3421
}
```

## Docker

```bash
docker-compose up
```

## Next Steps

1. Build React frontend (search + results display)
2. Add user authentication
3. Store recommendation history
4. Add batch generation & caching

See `HELP.md` for Maven info.
