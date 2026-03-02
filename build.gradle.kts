import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.3"
    id("com.github.johnrengelman.shadow") version "8.1.1" // 引入 shadow 插件
}

group = "cn.jarryleo"
version = "2.0"

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
    version = "2025.1.3"
    type = "IC"
    plugins = listOf("java", "Kotlin")
}


dependencies {
    //依赖 阿里云 百炼 大模型
    implementation("com.alibaba:dashscope-sdk-java:2.22.9")
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    buildSearchableOptions {
        enabled = false
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    patchPluginXml {
        sinceBuild.set("251")
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

tasks {
    shadowJar {
        archiveClassifier.set("") // 覆盖原始 jar
        mergeServiceFiles() // 合并服务文件

        // 排除 IntelliJ 平台已提供的依赖
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")

        // 重定位包名避免冲突（可选）
        relocate("com.alibaba", "shadow.com.alibaba")
    }

    // 让 build 任务依赖 shadowJar
    build {
        dependsOn(shadowJar)
    }
}