# Two-stage build: builder has the JDK + Maven, runtime only the JRE.
# Cuts the final image size from ~600MB to ~200MB.

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy wrapper and pom first so Docker caches the dependency layer
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B --no-transfer-progress

# Source goes after deps -- only this layer rebuilds when code changes
COPY src ./src
RUN ./mvnw clean package -DskipTests -B --no-transfer-progress

# Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

# Don't run as root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

COPY --from=builder /app/target/courtly-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

# Heap up to 75% of the container's memory limit (the JVM reads the limit on its own)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

# 90s start period gives Spring time to boot and connect to Postgres.
# wget is busybox's, already in the alpine base image
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD wget -qO- http://localhost:8080/api/v1/health || exit 1

# exec so java replaces the shell and gets SIGTERM from `docker stop` -
# otherwise the shell swallows it and Docker kills the app after 10s instead
# of letting Spring shut down cleanly
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
