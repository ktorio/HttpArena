plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(ktorLibs.plugins.ktor)
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

    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.exposed.json)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.r2dbc.pool)
}

ktor {
    fatJar {
        archiveFileName.set("ktor-httparena.jar")
    }
}

kotlin {
    jvmToolchain(21)
}
