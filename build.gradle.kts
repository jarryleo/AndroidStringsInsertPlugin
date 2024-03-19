plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "cn.jarryleo"
version = "1.1"

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

//编译器文件夹路径，根据自己的实际情况修改
val idePath = "D:/Android/Android Studio"

intellij {
    //version.set("2023.1.5")
    localPath.set(idePath)
    type.set("AI") // Target IDE Platform

    plugins.set(listOf("java", "Kotlin"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("241.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    runIde {
        ideDir.set(file(idePath))
    }
}
