# ── Stage 1: build shadow JAR ─────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle build.gradle ./
COPY flink/jobs/build.gradle flink/jobs/build.gradle
COPY flink/jobs/src flink/jobs/src
# settings.gradle includes producer and load-generator; create dirs so Gradle 9 doesn't reject them
RUN mkdir -p producer load-generator

RUN ./gradlew :flink-jobs:shadowJar :flink-jobs:shadowJarTradeStream --no-daemon

# ── Stage 2: Flink image with both job JARs ───────────────────────────────────
FROM flink:1.19.1-java17

COPY --from=builder /app/flink/jobs/build/libs/ohlcv-job.jar        /opt/flink/usrlib/ohlcv-job.jar
COPY --from=builder /app/flink/jobs/build/libs/trade-stream-job.jar  /opt/flink/usrlib/trade-stream-job.jar
