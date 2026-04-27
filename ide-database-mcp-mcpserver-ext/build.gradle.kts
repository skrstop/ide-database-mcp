plugins {
    id("java")
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform.module")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":ide-database-mcp-base"))
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    testCompileOnly("org.projectlombok:lombok:1.18.38")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.38")

    intellijPlatform {
        create("IU", "2025.2")
//        create("IU", "2025.3.4")
        bundledPlugin("com.intellij.database")
        bundledPlugin("com.intellij.mcpServer")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Gradle 9.x 严格模式下，Kotlin 编译产物与 IntelliJ Platform 插件追加的 META-INF 资源
// 可能重复（例如 ide-database-mcp-mcpserver-ext.kotlin_module），统一以 EXCLUDE 策略避免构建失败
tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        // 强制编译器忽略元数据版本不一致的问题
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
        // 顺便确保 JVM 目标版本一致
        jvmTarget = "21"
    }
}