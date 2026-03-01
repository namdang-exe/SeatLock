# Build stage — runs from the repo root as build context
# Usage: docker build -f notification-service/Dockerfile -t seatlock/notification-service .
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY common/build.gradle.kts common/build.gradle.kts
COPY common/src common/src
COPY notification-service/build.gradle.kts notification-service/build.gradle.kts
COPY notification-service/src notification-service/src

RUN chmod +x ./gradlew && \
    ./gradlew :notification-service:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/notification-service/build/libs/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
