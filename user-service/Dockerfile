# Build stage — runs from the repo root as build context
# Usage: docker build -f user-service/Dockerfile -t seatlock/user-service .
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY common/build.gradle.kts common/build.gradle.kts
COPY common/src common/src
COPY user-service/build.gradle.kts user-service/build.gradle.kts
COPY user-service/src user-service/src

RUN chmod +x ./gradlew && \
    ./gradlew :user-service:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/user-service/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
