# SeatLock — Bug Log

Brief record of significant bugs and their fixes. Add new entries at the top.

---

## [2026-02-28] Testcontainers fails with HTTP 400 on Docker Desktop 4.60.1

**Stage:** 2 (Testing Infrastructure)

**Symptom:**
```
IllegalStateException: Could not find a valid Docker environment
BadRequestException (Status 400) from both TCP and Npipe strategies
```

**Root cause:**
Docker Desktop 4.60.1 enforces a minimum API version of 1.44. Testcontainers 1.21.0 uses a shaded copy of docker-java that defaults to API version 1.32. Every request goes to `/v1.32/...` → Docker returns 400.

Setting `DOCKER_API_VERSION=1.44` as an env var has no effect — the shaded docker-java does **not** read that env var. It reads the API version from the JVM system property `api.version`.

**Fix:**
Add `jvmArgs("-Dapi.version=1.44")` to every `integrationTest` task in every service's `build.gradle.kts`:

```kotlin
tasks.register<Test>("integrationTest") {
    ...
    jvmArgs("-Dapi.version=1.44")   // ← forces shaded docker-java to use v1.44
    ...
}
```

**Files changed:**
- `user-service/build.gradle.kts`
- `venue-service/build.gradle.kts`
- `booking-service/build.gradle.kts`
- `notification-service/build.gradle.kts`
- `~/.testcontainers.properties` — set to `testcontainers.reuse.enable=true` only (no strategy overrides needed)

---
