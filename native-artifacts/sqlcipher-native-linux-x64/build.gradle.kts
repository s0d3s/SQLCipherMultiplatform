plugins {
    `java-library`
    id("maven-publish")
    id("signing")
}

java {
    withSourcesJar()
    withJavadocJar()
}
