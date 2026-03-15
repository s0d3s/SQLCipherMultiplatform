pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SQLCipherMultiplatform"

include(
    ":kmp-api",
    ":jdbc-sqlcipher-jvm",
    ":native-bridge",
    ":samples:desktop-jvm",
    ":samples:kmp-basic-app",
    ":samples:kmp-sqldelight-app"
)
