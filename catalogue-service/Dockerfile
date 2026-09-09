# ════════════════════════════════════════════════════════════════════════════
# Stage 1 — Build
# Uses the full Maven + JDK image to compile and package the application.
# This stage is discarded after the build; it never ships in the final image.
# ════════════════════════════════════════════════════════════════════════════
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy the POM first to leverage Docker layer caching for dependencies.
# If pom.xml hasn't changed, Maven won't re-download dependencies.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build the executable JAR (skip tests in build stage —
# tests are run locally in Phase 5 before Dockerisation).
COPY src ./src
RUN mvn package -DskipTests -B

# ════════════════════════════════════════════════════════════════════════════
# Stage 2 — Runtime
# Slim JRE-only image — no Maven, no full JDK, no source code.
# ════════════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:17-jre-alpine

# Good practice: run as a non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy only the fat JAR produced by Stage 1
COPY --from=builder /app/target/catalogue-service-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
