plugins {
    kotlin("jvm") version "2.3.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "io.github.zayitmcp"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:0.12.0")
    implementation("org.apache.lucene:lucene-core:10.3.2")
    implementation("org.apache.lucene:lucene-analysis-common:10.3.2")
    implementation("org.apache.lucene:lucene-queryparser:10.3.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-server-cio:3.1.3")
    implementation("io.ktor:ktor-server-core:3.1.3")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(19)
}

tasks.shadowJar {
    archiveBaseName.set("zayit-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    mergeServiceFiles()
}
