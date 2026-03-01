plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "seatlock"

include(
    "common",
    "user-service",
    "venue-service",
    "booking-service",
    "notification-service"
)
