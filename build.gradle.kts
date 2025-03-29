plugins {
    kotlin("jvm") version "2.1.0"
}

group = "me.heartalborada"
version = "0.0.1"

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
    }
}

tasks.jar {
    enabled = false
}