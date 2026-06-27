import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.intellij") version "1.17.3"
    id("com.github.johnrengelman.shadow") version "8.1.1" // 引入 shadow 插件
}

group = "cn.jarryleo"
version = "3.11.0"

repositories {
    mavenCentral()
    google()
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
    plugins = listOf("java")
}


dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.api-client:google-api-client:2.7.2")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20241203-2.0.0")
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
        untilBuild.set(provider { null })
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

    }

    // 让 build 任务依赖 shadowJar
    build {
        dependsOn(shadowJar)
    }
}
