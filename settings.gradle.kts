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
    ":native-artifacts:sqlcipher-native-windows-x64",
    ":native-artifacts:sqlcipher-native-linux-x64",
    ":native-artifacts:sqlcipher-native-linux-arm64",
    ":native-artifacts:sqlcipher-native-macos-x64",
    ":native-artifacts:sqlcipher-native-macos-arm64",
    ":samples:desktop-jvm",
    ":samples:kmp-basic-app",
    ":samples:kmp-sqldelight-app"
)
