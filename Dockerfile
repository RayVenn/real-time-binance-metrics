# ── Stage 1: build fat JAR ────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle build.gradle ./
COPY producer/build.gradle producer/build.gradle
COPY producer/src producer/src
# settings.gradle includes flink-jobs; create the dir so Gradle 9 doesn't reject it
RUN mkdir -p flink/jobs

RUN ./gradlew :producer:jar --no-daemon

# ── Stage 2: minimal JRE runtime ─────────────────────────────────────────────
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

RUN groupadd --gid 1000 appuser && useradd --uid 1000 --gid 1000 --no-create-home appuser
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/producer/build/libs/producer.jar app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8080/health

CMD ["java", "-jar", "app.jar"]
