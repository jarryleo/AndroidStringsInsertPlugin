import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "cn.jarryleo"
version = "1.7"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
//IC - IntelliJ IDEA Community Edition
//IU - IntelliJ IDEA Ultimate Edition
//CL - CLion
//PY - PyCharm Professional Edition
//PC - PyCharm Community Edition
//PS - PhpStorm
//RD - Rider
//GO - GoLand
//AI - Android Studio
//RR - Rust Rover
//JPS - JPS-only
//GW - Gateway

intellij {
    version = "2025.3.1"
    type = "IC"
    plugins = listOf("java", "Kotlin")
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("253.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
