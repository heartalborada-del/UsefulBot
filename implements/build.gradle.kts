group = "me.heartalborada.implements"

plugins {
    id("com.gradleup.shadow") version "8.3.6"
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jsoup:jsoup:1.19.1")
    implementation(project(":commons"))
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3")
    implementation("com.google.guava:guava:33.4.6-jre")
    implementation("com.h2database:h2:2.2.224")
}

tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "MainKt"
        }
    }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
    }
}