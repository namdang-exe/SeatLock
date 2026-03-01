# Build stage — runs from the repo root as build context
# Usage: docker build -f venue-service/Dockerfile -t seatlock/venue-service .
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY common/build.gradle.kts common/build.gradle.kts
COPY common/src common/src
COPY venue-service/build.gradle.kts venue-service/build.gradle.kts
COPY venue-service/src venue-service/src

RUN chmod +x ./gradlew && \
    ./gradlew :venue-service:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/venue-service/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
