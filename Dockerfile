# Stage 1: Build shaded executable jar
FROM maven:3.9-eclipse-temurin-21-jammy AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -B

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

RUN groupadd -r appgroup && useradd -r -g appgroup -d /app appuser

COPY --from=builder /build/target/reverse-proxy-rate-limiter-1.0.0.jar /app/reverse-proxy-rate-limiter.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/_admin/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "/app/reverse-proxy-rate-limiter.jar"]
CMD ["server", "--port", "8080", "--upstream-host", "127.0.0.1", "--upstream-port", "8081"]
