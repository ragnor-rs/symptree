# symptree messaging — Telegram bot + embedded health/outgoing HTTP server.
# Talks to core over HTTP only (no compile-time dep); reads its config from env
# (MessagingConfig.fromEnv). Single-tenant per JVM: one bot per CLINIC_SLUG.
FROM eclipse-temurin:21-jre

# curl for the HEALTHCHECK (jre image ships without it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Fat jar built by `./gradlew :messaging:shadowJar` (archiveBaseName = "messaging").
COPY messaging/build/libs/messaging.jar /app/messaging.jar

# The module serves only POST /bot-internal/outgoing (no /health route). A GET on
# that path returns 405 once the embedded server is up — a plain `curl` (no -f)
# still exits 0 on any HTTP response, so this passes iff the port is listening and
# fails (curl exit 7) only when the server isn't accepting connections yet.
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -s -o /dev/null http://localhost:8084/bot-internal/outgoing || exit 1

ENTRYPOINT ["java", "-Xmx384m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-jar", "/app/messaging.jar"]
