import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
}

group = "me.heartalborada"
version = "1.3.0"

val exposedVersion = project.property("exposedVersion").toString()
subprojects {
    version = rootProject.version
    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
        dependencies {
            add("testImplementation", kotlin("test-junit5"))
            add("testImplementation", "org.junit.jupiter:junit-jupiter:6.1.2")
            add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            add("implementation", "com.squareup.okhttp3:okhttp:5.4.0")
            add("implementation", "com.google.code.gson:gson:2.14.0")
            add("implementation", "org.slf4j:slf4j-api:2.0.18")
            add("implementation", "commons-io:commons-io:2.22.0")
            add("implementation", "org.jetbrains.exposed:exposed-core:${exposedVersion}")
            add("implementation", "org.jetbrains.exposed:exposed-jdbc:${exposedVersion}")
            add("implementation", "org.jetbrains.exposed:exposed-dao:${exposedVersion}")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register("shadowJar") {
    group = "build"
    description = "Builds the executable shadow JAR from the implements module."
    dependsOn(":implements:shadowJar")
}

tasks.register("loaderJar") {
    group = "build"
    description = "Builds the executable loader JAR that resolves dependencies from Maven."
    dependsOn(":implements:jar")
}
