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
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("com.google.code.gson:gson:2.12.1")
        implementation("org.slf4j:slf4j-api:2.0.17")
        implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    }
}

tasks.jar {
    enabled = false
}