pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
    versionCatalogs {
        val ktorVersion = providers.gradleProperty("ktorVersion").getOrElse("3.6.1")
        create("ktorLibs").from("io.ktor:ktor-version-catalog:$ktorVersion")
    }
}

rootProject.name = "ktor-tuned-httparena"
