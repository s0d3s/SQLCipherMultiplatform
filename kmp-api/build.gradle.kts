plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting
        val jvmMain by getting {
            dependencies {
                implementation(project(":jdbc-sqlcipher-jvm"))
            }
        }
    }
}
