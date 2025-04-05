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
}

tasks {
    jar {
        // 设置主类，导出的jar可以直接运行
        manifest {
            attributes["Main-Class"] = "MainKt" // 格式为包名+类名+“Kt”（因为kotlin编译后生成的java类会自动加上kt）
        }
    }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
    }
}