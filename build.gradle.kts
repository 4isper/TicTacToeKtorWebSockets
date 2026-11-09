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
            // commonTest is exercised on the JVM. Browser tests need a Karma/Chrome
            // runner plus npm packages from the Kotlin karma fork, which an offline
            // build cannot resolve; enabling this also makes CI depend on a browser.
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
            implementation(libs.kotlin.css)
            implementation(libs.logback.classic)
            implementation(libs.sqlite.jdbc)
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
    // detekt 2.x registers one task per KMP source set; the plain `detekt` task that
    // `check` depends on has no sources of its own, so give it the whole source tree.
    source.setFrom(files("src"))
}

tasks.named("check") {
    dependsOn(tasks.spotlessCheck, tasks.detekt)
    // type-resolution rules only run in the compilation tasks
    dependsOn("detektMainJvm", "detektTestJvm")
}

tasks.configureEach {
    if (name == "jvmRun") {
        // the distribution task also refreshes build/dist, which the server serves
        dependsOn("jsBrowserDistribution")
    }
}

// A single runnable jar for deployments; the JS bundle is served separately from build/dist.
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Bundles the JVM classes, resources and every runtime dependency into one runnable jar"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "com.m4isper.tictactoe.ApplicationKt") }
    dependsOn("jvmJar", "jsBrowserProductionWebpack")
    from({ configurations.getByName("jvmRuntimeClasspath").map { if (it.isDirectory) it else zipTree(it) } })
    from(tasks.named("jvmJar").map { zipTree(it.outputs.files.singleFile) })
}
