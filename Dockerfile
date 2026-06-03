# ── Stage 1: Build ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# Copy Gradle wrapper and config first (layer-cache friendly)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Make wrapper executable and pre-download dependencies
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon -q || true

# Copy source and build the fat JAR
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy fat JAR from builder stage
COPY --from=builder /build/build/libs/xml-pipeline.jar app.jar

# Copy the 100 URLs list
COPY urls.json /app/urls.json

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]