plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

group = "com.m4isper"
version = "0.0.1"

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    js {
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
            testTask {
                enabled = false
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.server.call.logging)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.html.builder)
            implementation(libs.logback.classic)
        }
        jsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlin.test.junit)
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
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

tasks.configureEach {
    if (name == "jvmRun") {
        dependsOn("jsBrowserProductionWebpack")
    }
}
