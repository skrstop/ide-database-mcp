pluginManagement {
    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        gradlePluginPortal()
    }

    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.jetbrains.intellij.platform" && requested.version != null) {
                useModule("org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:${requested.version}")
            }
        }
    }
}

rootProject.name = "ide-database-mcp"
