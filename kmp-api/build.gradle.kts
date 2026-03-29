plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("maven-publish")
    id("signing")
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    androidTarget {
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

        val androidMain by getting {
            dependencies {
                implementation("net.zetetic:android-database-sqlcipher:4.5.4")
                implementation("androidx.sqlite:sqlite:2.2.0")
            }
        }
    }
}

android {
    namespace = "io.github.s0d3s.sqlcipher.multiplatform.api"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
