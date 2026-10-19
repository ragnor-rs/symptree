# symptree core — Ktor REST backend over Postgres.
# Runs its own migrations at startup (MigrationRunner reads the relative `database/`
# dir), so docker-compose bind-mounts ./database at /app/database (the WORKDIR).
FROM eclipse-temurin:21-jre

# curl is used by the HEALTHCHECK / compose service_healthy gate; the jre image
# doesn't ship it. --no-install-recommends keeps the layer small.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Fat jar built by `./gradlew :core:shadowJar` (archiveBaseName = "core").
COPY core/build/libs/core.jar /app/core.jar

# core reads PORT from env (CoreConfig.fromEnv, default 8080); compose sets it to 8080.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-Xmx512m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-jar", "/app/core.jar"]
