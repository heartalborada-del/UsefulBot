group = "me.heartalborada.commons"
val exposedVersion: String by project

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("com.h2database:h2:2.2.224")
    implementation("org.apache.pdfbox:pdfbox:3.0.4")
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
    implementation("org.jetbrains.exposed:exposed-java-time:${exposedVersion}")
}
