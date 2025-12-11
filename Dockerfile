# Stage 1: Build the application
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom.xml
COPY mvnw .
COPY mvnw.cmd .
COPY .mvn .mvn
COPY pom.xml .

# Copy source code
COPY src src

# Build the application (skip tests)
RUN chmod +x mvnw && \
    ./mvnw clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Copy the built JAR from builder stage
COPY --from=builder /app/target/nextflix-*.jar app.jar

# Expose ports
# 8080 for the application
# 4317 for OpenTelemetry OTLP
EXPOSE 8080 4317

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/api/recommendations/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
