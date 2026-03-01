# Build stage — runs from the repo root as build context
# Usage: docker build -f booking-service/Dockerfile -t seatlock/booking-service .
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY common/build.gradle.kts common/build.gradle.kts
COPY common/src common/src
COPY booking-service/build.gradle.kts booking-service/build.gradle.kts
COPY booking-service/src booking-service/src

RUN chmod +x ./gradlew && \
    ./gradlew :booking-service:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/booking-service/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
