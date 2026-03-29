plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":jdbc-sqlcipher-jvm"))
}

application {
    mainClass.set("io.github.s0d3s.sqlcipher.multiplatform.samples.jdbc.MainKt")
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeLibBasename = providers.gradleProperty("native.lib.basename").orElse("sqlcipher_jni")

tasks.named<JavaExec>("run") {
    dependsOn(":native-bridge:buildNative")
    jvmArgs("-Dsqlcipher.native.lib.basename=${nativeLibBasename.get()}")
}

tasks.register<JavaExec>("verifySample") {
    group = "verification"
    description = "Runs JDBC sample end-to-end checks (CRUD + encrypted-at-rest + wrong-key rejection)"
    dependsOn(":native-bridge:buildNative", "classes")

    mainClass.set(application.mainClass)
    classpath(sourceSets.main.get().runtimeClasspath)

    jvmArgs("-Dsqlcipher.native.lib.basename=${nativeLibBasename.get()}")
}
