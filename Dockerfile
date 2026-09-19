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
# python3-aubio pulls in python3 + numpy automatically; used by AudioFeatureService for
# per-track tempo/energy analysis the same way ffmpeg is used for transcoding — a small,
# well-scoped native dependency rather than a DSP implementation in the JVM.
#
# i965-va-driver and intel-media-va-driver are VAAPI backends for two different Intel
# GPU generations (pre-Broadwell and Broadwell+ respectively, per ffmpeg's own driver
# probe order) — both installed since this image is not built once per host GPU, and
# ffmpeg only opens the one that actually matches what it finds. Inert with no effect
# on a host that never bind-mounts /dev/dri in, or one with no Intel GPU at all — see
# MediaProperties#transcodeHwaccelEnabled for the flag that actually turns this on.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg python3-aubio i965-va-driver intel-media-va-driver \
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
