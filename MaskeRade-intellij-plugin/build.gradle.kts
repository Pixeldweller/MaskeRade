plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.pixeldweller"
version = "0.1-alpha"

repositories {
    // JUnit 4 is needed at test runtime by the IntelliJ test class loader; it is taken from the local Maven repository
    mavenLocal {
        content {
            includeGroup("junit")
            includeGroup("org.hamcrest")
        }
    }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// -PlocalIdePath=/path/to/idea (or gradle.properties) uses an installed IDE instead of downloading one
val localIdePath: String? = providers.gradleProperty("localIdePath").orNull

dependencies {
    intellijPlatform {
        if (localIdePath != null) local(localIdePath) else intellijIdea("2025.3.4")
        bundledPlugin("com.intellij.java")
    }

    // plain JUnit for the IntelliJ-free core (veil, scan, mirror) — already in the local Gradle cache
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    // the matching java-compiler-ant-tasks for a local IDE build is usually not cached (offline)
    instrumentCode = localIdePath == null

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            0.1-alpha: Initial release — term list with automatic spellings, candidate scanner,
            pseudonymized mirror of the project for AI agents and unveiling of their changes.
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }

    test {
        useJUnitPlatform()
        // the end-to-end test works on a copy of the sample project next to the plugin
        systemProperty("maskerade.sample", layout.projectDirectory.dir("../MaskeRade-sample").asFile.absolutePath)
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
