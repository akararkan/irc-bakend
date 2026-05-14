# syntax=docker/dockerfile:1.7

# ════════════════════════════════════════════════════════════════════════════
#  IRC PLATFORM — PRODUCTION IMAGE
#
#  Two-stage build:
#    1. builder  — JDK 25 + Maven, compiles a runnable fat-jar
#    2. runtime  — minimal JRE 25, runs as a non-root user
#
#  Railway:   image is detected automatically. Set SPRING_PROFILES_ACTIVE=prod
#             plus the env vars listed in .env.example. PORT is injected by
#             Railway and honoured via the base server.port config.
# ════════════════════════════════════════════════════════════════════════════

# ───────── 1. BUILDER ──────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Maven wrapper + pom first → maximises Docker layer cache for dependencies.
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./

# Pre-download dependencies (cached unless pom.xml changes).
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B -DskipTests dependency:go-offline

# Now bring in sources and build the fat-jar.
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B -DskipTests package && \
    cp target/*.jar /build/app.jar


# ───────── 2. RUNTIME ──────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

# Non-root user for least privilege.
RUN useradd --system --create-home --uid 1000 --shell /usr/sbin/nologin irc
USER irc
WORKDIR /home/irc

COPY --from=builder /build/app.jar /home/irc/app.jar

# Railway provides $PORT — Spring Boot's server.port already reads it.
# Documented EXPOSE is informational; the actual port is whatever PORT is set to.
EXPOSE 8080

# Active profile defaults to prod inside the container (overridable).
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Health check — Railway uses its own healthcheck (configured in railway.toml),
# this one is for `docker run` parity and local Compose testing.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS "http://localhost:${PORT:-8080}/actuator/health" || exit 1

ENTRYPOINT ["java","-jar","/home/irc/app.jar"]
