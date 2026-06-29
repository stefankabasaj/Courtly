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

# wget is needed for HEALTHCHECK and isn't in alpine by default
RUN apk add --no-cache wget

# Don't run as root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

COPY --from=builder /app/target/courtly-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

# Container-aware JVM flags so heap respects the container memory limit
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# 90s start period gives Spring time to boot and connect to Postgres
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD wget -qO- http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
