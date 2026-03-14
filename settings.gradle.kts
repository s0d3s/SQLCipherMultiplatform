pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "sqlite-multicrypt"

include(
    ":kmp-api",
    ":jdbc-sqlcipher-jvm",
    ":native-bridge",
    ":samples:desktop-jvm",
    ":samples:kmp-sqldelight-app"
)
