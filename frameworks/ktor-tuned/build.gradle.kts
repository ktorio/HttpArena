plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.ktor)
}

group = "com.httparena"
version = "1.0.0"

application {
    mainClass = "com.httparena.ApplicationKt"
}

dependencies {
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.websockets)
    implementation(ktorLibs.server.htmlBuilder)

    implementation(libs.ghost.serialization)
    ksp(libs.ghost.compiler)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    runtimeOnly(variantOf(libs.netty.native.epoll) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.netty.native.openssl) { classifier("linux-x86_64") })

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

// Prevent the BOM import in the gradle plugin from overriding the Ktor version
val ktorVersion = providers.gradleProperty("ktorVersion").getOrElse(libs.versions.ktor.plugin.get())

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") {
            useVersion(ktorVersion)
            because("Always use the ktorVersion override instead of the Ktor plugin BOM version")
        }
    }
}

ktor {
    fatJar {
        archiveFileName.set("ktor-tuned-httparena.jar")
    }
}

kotlin {
    jvmToolchain(25)
}
