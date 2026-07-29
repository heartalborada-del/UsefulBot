import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.SourceSetContainer
import java.security.MessageDigest

group = "me.heartalborada.implements"

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.6.1"
}

base {
    archivesName.set("UsefulBot")
}

dependencies {
    implementation("org.jsoup:jsoup:1.22.2")
    implementation(project(":commons"))
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")
    implementation("com.google.guava:guava:33.6.0-jre")
    implementation("com.h2database:h2:2.4.240")
}

val loaderResourcesDirectory = layout.buildDirectory.dir("generated/loader-resources")
val generateLoaderDependencies = tasks.register("generateLoaderDependencies") {
    val runtimeClasspath = configurations.runtimeClasspath
    val outputFile = loaderResourcesDirectory.map {
        it.file("META-INF/usefulbot-dependencies.tsv")
    }

    inputs.files(runtimeClasspath)
    outputs.file(outputFile)

    doLast {
        val dependencies = runtimeClasspath.get()
            .incoming
            .artifacts
            .artifacts
            .mapNotNull { artifact ->
                val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: return@mapNotNull null
                val digest = MessageDigest.getInstance("SHA-256")
                artifact.file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                listOf(
                    component.group,
                    component.module,
                    component.version,
                    artifact.file.name,
                    sha256,
                ).joinToString("\t")
            }
            .distinct()
            .sorted()

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(dependencies.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}

val commonsSourceSets = project(":commons").extensions.getByType<SourceSetContainer>()

sourceSets {
    main {
        resources.srcDir(loaderResourcesDirectory)
    }
}

tasks {
    processResources {
        dependsOn(generateLoaderDependencies)
    }
    jar {
        dependsOn(":commons:classes")
        from(commonsSourceSets.named("main").map { sourceSet -> sourceSet.output })
        manifest {
            attributes["Main-Class"] = "me.heartalborada.loader.DependencyLoader"
        }
    }
    shadowJar {
        archiveClassifier.set("all")
        doFirst {
            manifest.attributes["Main-Class"] = "MainKt"
        }
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        append("META-INF/DEPENDENCIES")
        append("META-INF/LICENSE")
        append("META-INF/LICENSE.txt")
        append("META-INF/NOTICE")
        append("META-INF/NOTICE.txt")
        exclude("META-INF/usefulbot-dependencies.tsv")
        exclude("me/heartalborada/loader/**")
        exclude("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    assemble {
        dependsOn(shadowJar)
    }
}
