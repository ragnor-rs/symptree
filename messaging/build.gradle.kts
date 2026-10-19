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

    // Telegram bot
    api("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.3.0")

    // HTTP (WhatsApp Cloud API + backend client + Telegram response unwrapping)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.12.0")

    // Ktor 3.x server — hosts the inbound /bot-internal/outgoing endpoint (FR-W03362 AC.9)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // YAML parser (kotlinx-serialization-based) — messaging-side MessageCatalog (FR-W03362 AC.9)
    implementation("com.charleskorn.kaml:kaml:0.83.0")

    // Coroutines + Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.WARN
    mergeServiceFiles()

    archiveBaseName.set("messaging")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes(mapOf("Main-Class" to "com.symptree.messaging.MessagingMainKt"))
    }
}
