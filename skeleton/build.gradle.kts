plugins {
    java
}

repositories { mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

val quartetCli: Configuration by configurations.creating

// The environment these tests run against is named in quartet.yaml, so plain ./gradlew test and Quartet agree.
val configuredEnvironment: String = file("quartet.yaml").takeIf { it.exists() }?.readLines()
    ?.firstOrNull { it.startsWith("environment:") }?.substringAfter(":")?.trim()
    ?: "context/environments/default.yaml"

dependencies {
    testImplementation("io.quartetqe:quartet-runtime:0.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    quartetCli("io.quartetqe:quartet-generator:0.1.0")
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    environment("QUARTET_ENV", System.getenv("QUARTET_ENV") ?: "$projectDir/$configuredEnvironment")
    testLogging {
        events("passed", "failed", "skipped")
        showExceptions = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
    doFirst { delete(layout.buildDirectory.dir("quartet-report")) }
}

// Used by the ./quartet-qe script to start the command line tool.
tasks.register("quartetClasspath") {
    val out = layout.buildDirectory.file("quartet.classpath")
    val cli = quartetCli
    inputs.files(cli)
    outputs.file(out)
    doLast { out.get().asFile.writeText(cli.asPath) }
}
