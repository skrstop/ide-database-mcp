plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.skrstop.ide"
version = "0.1.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
//        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")

    intellijPlatform {
        intellijIdeaUltimate("2022.3.3")
//        intellijIdeaUltimate("2025.3.4")
        bundledPlugin("com.intellij.database")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "223"
            untilBuild = provider { null }
        }
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }
}
