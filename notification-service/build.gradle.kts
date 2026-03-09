plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.1")
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:3.2.1")
    }
}

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.cloud:spring-cloud-starter-vault-config")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}

configurations {
    getByName("integrationTestImplementation").extendsFrom(getByName("testImplementation"))
    getByName("integrationTestRuntimeOnly").extendsFrom(getByName("testRuntimeOnly"))
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    jvmArgs("-Dapi.version=1.44")
    (project.findProperty("dockerHost") as String?)?.let { environment("DOCKER_HOST", it) }
    (project.findProperty("dockerApiVersion") as String?)?.let { environment("DOCKER_API_VERSION", it) }
}
