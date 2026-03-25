plugins {
    id("java")
    id("org.jetbrains.intellij.platform.module")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
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

    intellijPlatform {
        create("IU", "2022.3.3")
        bundledPlugin("com.intellij.database")
    }
}
