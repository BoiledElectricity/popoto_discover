import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.popotomodem"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.popotomodem.discover.MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("popoto-discover")
    archiveClassifier.set("")
}
