# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build

# Dependencies are copied and resolved before the source. Docker caches layers by
# content, so editing a Java file re-runs only the compile step; without this
# split every rebuild re-downloads the entire dependency tree.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline -DskipTests

COPY src/ src/
# Tests need Docker-in-Docker for Testcontainers, so they run in CI rather than
# in the image build. The image is built from a commit CI has already tested.
RUN ./mvnw -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Unprivileged user: a container process that cannot write to its own filesystem
# is a much smaller problem when something else goes wrong.
RUN addgroup -S seatlock && adduser -S seatlock -G seatlock
COPY --from=build --chown=seatlock:seatlock /build/target/*.jar app.jar
USER seatlock

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then respects whatever the
# container limit turns out to be, instead of being told a number that is wrong
# on every host with a different memory allocation.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
