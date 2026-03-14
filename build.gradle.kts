plugins {
    kotlin("multiplatform") version "1.9.24" apply false
    kotlin("jvm") version "1.9.24" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    base
}

allprojects {
    group = "io.github.s0d3s.sqlcipher.multiplatform"
    version = "0.1.0-SNAPSHOT"
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val sqlcipherRef = providers.gradleProperty("sqlcipher.ref").orNull

tasks.register<Exec>("initSqlcipherSubmodule") {
    group = "sqlcipher"
    description = "Initializes SQLCipher upstream git submodule"
    commandLine("git", "submodule", "update", "--init", "--recursive", "third_party/sqlcipher/upstream")
}

tasks.register<Exec>("updateSqlcipherAmalgamation") {
    group = "sqlcipher"
    description = "Regenerates third_party/sqlcipher/sqlite3.c and sqlite3.h from upstream submodule sources"
    dependsOn("initSqlcipherSubmodule")

    workingDir = rootDir

    if (isWindowsHost) {
        val command = mutableListOf(
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            "${rootDir.absolutePath}/scripts/update-sqlcipher-amalgamation.ps1"
        )

        if (!sqlcipherRef.isNullOrBlank()) {
            command += listOf("-Ref", sqlcipherRef)
        }

        commandLine(command)
    } else {
        val command = mutableListOf(
            "bash",
            "${rootDir.absolutePath}/scripts/update-sqlcipher-amalgamation.sh"
        )

        if (!sqlcipherRef.isNullOrBlank()) {
            command += sqlcipherRef
        }

        commandLine(command)
    }
}
