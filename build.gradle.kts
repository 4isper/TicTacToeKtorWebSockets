
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

group = "com.m4isper"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlin.test.junit)
}

spotless {
    kotlin {
        ktlint("1.8.0")
            .editorConfigOverride(mapOf("ktlint_standard_no-wildcard-imports" to "disabled"))
    }
    kotlinGradle {
        ktlint("1.8.0")
            .editorConfigOverride(mapOf("ktlint_standard_no-wildcard-imports" to "disabled"))
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt.yml"))
}

tasks.named("check") {
    dependsOn(tasks.spotlessCheck, tasks.detekt)
}
