plugins {
    kotlin("jvm") version "2.1.0"
}

group = "me.heartalborada"
version = "1.0.0"

val exposedVersion: String by project
allprojects {
    version = rootProject.version
    repositories {
        mavenCentral()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    dependencies {
        testImplementation(kotlin("test"))
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("com.google.code.gson:gson:2.12.1")
        implementation("org.slf4j:slf4j-api:2.0.17")
        implementation("commons-io:commons-io:2.18.0")
        implementation("org.jetbrains.exposed:exposed-core:${exposedVersion}")
        implementation("org.jetbrains.exposed:exposed-jdbc:${exposedVersion}")
        implementation("org.jetbrains.exposed:exposed-dao:${exposedVersion}")
    }
}

tasks.jar {
    enabled = false
}