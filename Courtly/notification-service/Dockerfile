# Same two-stage build as the API
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B --no-transfer-progress

COPY src ./src
RUN ./mvnw clean package -DskipTests -B --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

COPY --from=builder /app/target/courtly-notifications-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1

# exec so java gets SIGTERM and Kafka gets a clean consumer shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
