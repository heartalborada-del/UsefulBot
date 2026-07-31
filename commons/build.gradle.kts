plugins {
    kotlin("jvm")
}

group = "me.heartalborada.commons"
val exposedVersion = project.property("exposedVersion").toString()

dependencies {
    testImplementation("com.h2database:h2:2.4.240")
    implementation("org.apache.pdfbox:pdfbox:3.0.8") {
        exclude(group = "commons-logging", module = "commons-logging")
    }
    implementation("org.slf4j:jcl-over-slf4j:2.0.18")
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
    implementation("org.jetbrains.exposed:exposed-java-time:${exposedVersion}")
}
