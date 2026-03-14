plugins {
    kotlin("multiplatform") version "1.9.24" apply false
    kotlin("jvm") version "1.9.24" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    base
}

allprojects {
    group = "dev.boosted.sqlcipher"
    version = "0.1.0-SNAPSHOT"
}
