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
    ":sqlcipher-multiplatform",
    ":sqlcipher-multiplatform-jdbc-core",
    ":native-bridge",
    ":native-artifacts:sqlcipher-multiplatform-jdbc-windows-x64",
    ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-x64",
    ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-arm64",
    ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-x64",
    ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-arm64",
    ":samples:desktop-jvm",
    ":samples:kmp-basic-app",
    ":samples:kmp-sqldelight-app"
)

project(":sqlcipher-multiplatform").projectDir = file("sqlcipher-multiplatform")
project(":sqlcipher-multiplatform-jdbc-core").projectDir = file("sqlcipher-multiplatform-jdbc-core")

project(":native-artifacts:sqlcipher-multiplatform-jdbc-windows-x64").projectDir =
    file("native-artifacts/sqlcipher-multiplatform-jdbc-windows-x64")
project(":native-artifacts:sqlcipher-multiplatform-jdbc-linux-x64").projectDir =
    file("native-artifacts/sqlcipher-multiplatform-jdbc-linux-x64")
project(":native-artifacts:sqlcipher-multiplatform-jdbc-linux-arm64").projectDir =
    file("native-artifacts/sqlcipher-multiplatform-jdbc-linux-arm64")
project(":native-artifacts:sqlcipher-multiplatform-jdbc-macos-x64").projectDir =
    file("native-artifacts/sqlcipher-multiplatform-jdbc-macos-x64")
project(":native-artifacts:sqlcipher-multiplatform-jdbc-macos-arm64").projectDir =
    file("native-artifacts/sqlcipher-multiplatform-jdbc-macos-arm64")
