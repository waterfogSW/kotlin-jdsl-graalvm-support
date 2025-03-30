plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.hibernate.orm") version "6.6.11.Final"
    id("org.graalvm.buildtools.native") version "0.10.6"
    kotlin("plugin.jpa") version "1.9.25"
}

group = "org.waterfogsw"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // kotlin-jdsl
    val kotlinJdslVersion = "3.5.5"
    implementation("com.linecorp.kotlin-jdsl:jpql-dsl:$kotlinJdslVersion")
    implementation("com.linecorp.kotlin-jdsl:jpql-render:$kotlinJdslVersion")
    implementation("com.linecorp.kotlin-jdsl:spring-data-jpa-support:$kotlinJdslVersion")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

hibernate {
    enhancement {
        enableAssociationManagement = true
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

graalvmNative {
    toolchainDetection = false // Explicitly use the configured Java toolchain
    binaries.named("main") { // Correct way to configure the main binary
        javaLauncher = javaToolchains.launcherFor(java.toolchain) // Ensure correct Java launcher
        buildArgs.add("--initialize-at-build-time=org.jetbrains.kotlin")
        buildArgs.add("--initialize-at-run-time=com.mysql.cj.log.StandardLogger") // MySQL Driver logging
        buildArgs.add("--initialize-at-run-time=com.mysql.cj.NativeSession") // MySQL Driver native session
        buildArgs.add("--initialize-at-run-time=com.mysql.cj.jdbc.AbandonedConnectionCleanupThread") // MySQL Driver cleanup thread
        buildArgs.add("--initialize-at-run-time=io.netty.channel.epoll.Epoll") // If using Netty based components
        buildArgs.add("--initialize-at-run-time=org.hibernate") // Initialize Hibernate runtime components
        buildArgs.add("--initialize-at-run-time=org.hibernate.internal.util.ReflectHelper")
        buildArgs.add("--initialize-at-run-time=org.hibernate.reactive.provider.service.ReactiveGenerationTarget") // If using reactive
        buildArgs.add("--initialize-at-run-time=org.hibernate.validator.internal.engine.DefaultClockProvider") // Hibernate Validator
        // Add any other necessary runtime initializations
        // buildArgs.add("-H:+UnlockExperimentalVMOptions") // Sometimes needed for specific features
    }
}
