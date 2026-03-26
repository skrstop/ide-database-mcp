plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.skrstop.ide"
val baseVersion = "0.1.4"


// 1. 读取命令行参数，默认为 "modern" (包含 新版本特性)
// 可选值: "modern" 或 "legacy"
val buildFlavor = providers.gradleProperty("buildFlavor").getOrElse("modern")
val isModern = buildFlavor == "modern"

version = if (isModern) baseVersion else "${baseVersion}-legacy"
var jdkVersion = if (isModern) 21 else 17

java {
    toolchain {
        // 注意：如果你需要兼容 223 版本，严格来说应该用 Java 17。
        // 但如果你的代码确实只有在 Java 21 下才能跑，请保持不变。
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        bundledPlugin("com.intellij.database")
        if (isModern) {
            // Modern 版：包含 MCP，使用高版本 API 编译
//            intellijIdeaUltimate("2022.3.3")
//            intellijIdeaUltimate("2024.3.7")
//            intellijIdeaUltimate("2025.3.4")

            intellijIdeaUltimate("2025.2")
            bundledPlugin("com.intellij.mcpServer")
        } else {
            // Legacy 版：剥离 MCP，使用最低兼容版本编译
            intellijIdeaUltimate("2022.3.3")
            bundledPlugin("com.intellij.database")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    implementation(project(":ide-database-mcp-base"))
    if (isModern) {
        implementation(project(":ide-database-mcp-mcpserver-ext"))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 5. 动态划定发布兼容范围
            if (isModern) {
                // Modern 包含 MCP 功能，供较新的 IDE 使用
                sinceBuild = "252"
                untilBuild = provider { null }
            } else {
                // Legacy 无 MCP 功能，供旧版本 IDE 完美平替使用
                sinceBuild = "223"
                untilBuild = "251.*"
            }
        }
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }
    // 6. 动态修改 META-INF/plugin.xml 里的依赖声明
    withType<ProcessResources> {
        filesMatching("META-INF/plugin.xml") {
            if (isModern) {
                // Modern 版：将占位符替换为真实的依赖声明
                filter { line ->
                    line.replace(
                        "<depends-mcp-server />",
                        """<depends optional="true" config-file="mcpserver-integration.xml">com.intellij.mcpServer</depends>"""
                    )
                }
            } else {
                // Legacy 版：直接抹除占位符，彻底切断与 MCP 的关联
                filter { line ->
                    line.replace("<depends-mcp-server />", "")
                }
            }
        }
    }

    named<org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask>("buildPlugin") {
        archiveBaseName.set(if (isModern) "ide-database-mcp" else "ide-database-mcp")
    }
}