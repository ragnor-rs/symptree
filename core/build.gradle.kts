plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.20"
    id("com.gradleup.shadow")
}

val ktorVersion = "3.0.3"

dependencies {
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    // Ktor 3.x
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Coroutines + Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // YAML parser (kotlinx-serialization-based) — used by MessageCatalog (FR-W3WDWH)
    implementation("com.charleskorn.kaml:kaml:0.83.0")

    // Postgres (raw JDBC — no ORM, no HikariCP)
    implementation("org.postgresql:postgresql:42.7.4")

    // API key hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    // OpenAI SDK — official Java client (M2 / FR-PWVTT4)
    implementation("com.openai:openai-java-core:3.5.1")
    implementation("com.openai:openai-java-client-okhttp:3.5.1")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
}

tasks {
    shadowJar {
        isZip64 = true
        duplicatesStrategy = DuplicatesStrategy.WARN
        mergeServiceFiles()

        archiveBaseName.set("core")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = "com.symptree.core.CoreAppKt"
        }
    }
}
