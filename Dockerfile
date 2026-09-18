# Multi-stage build: compile with the Gradle wrapper, run on a slim JRE.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -r -u 1001 appuser
COPY --from=build /app/build/libs/*.jar app.jar
# /app is owned by root by default; app.media.artwork-dir and app.media.teasers.output-dir
# both default to a relative "data/..." path under here, so appuser needs write access
# before it can create either. Made ahead of USER so a volume later mounted under
# /app/data (teaser clips) inherits this ownership on first mount rather than root's.
RUN mkdir -p /app/data/teaser-clips /app/data/artwork /app/data/music-previews \
    && chown -R appuser:appuser /app/data
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
