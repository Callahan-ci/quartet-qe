plugins {
    java
}

repositories { mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

val quartetCli: Configuration by configurations.creating

dependencies {
    testImplementation("io.quartetqe:quartet-runtime:0.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    quartetCli("io.quartetqe:quartet-generator:0.1.0")
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    environment("QUARTET_ENV", System.getenv("QUARTET_ENV") ?: "$projectDir/context/environments/default.yaml")
    testLogging { events("passed", "failed", "skipped") }
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
