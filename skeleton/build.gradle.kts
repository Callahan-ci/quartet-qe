plugins {
    java
}

repositories { mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

// Quartet QE is installed once per machine, not copied into this repo. Install it with ./get-started.sh in
// your Quartet QE folder. CI sets QUARTET_HOME (see the Jenkinsfile).
val quartetHome: String = System.getenv("QUARTET_HOME") ?: "${System.getProperty("user.home")}/.quartet/current"
if (!file("$quartetHome/lib").isDirectory) {
    throw GradleException(
        "Quartet QE is not installed on this machine (looked in $quartetHome). " +
            "Clone Quartet QE and run ./get-started.sh there, or set QUARTET_HOME."
    )
}

// The environment these tests run against is named in quartet.yaml, so plain ./gradlew test and Quartet agree.
val configuredEnvironment: String = file("quartet.yaml").takeIf { it.exists() }?.readLines()
    ?.firstOrNull { it.startsWith("environment:") }?.substringAfter(":")?.trim()
    ?: "context/environments/default.yaml"

dependencies {
    // The Quartet runtime with the test libraries it uses (JUnit and AssertJ).
    testImplementation(fileTree("$quartetHome/lib") { include("*.jar") })
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

// ./gradlew test -Pwatch opens a real browser window and slows each step down so you can follow it.
// Add -PslowMo=800 to slow it further. Without -Pwatch the browser stays hidden, as it must in CI.
val watch = project.hasProperty("watch")
val slowMo: String = (project.findProperty("slowMo") as String?) ?: "400"

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    // End-to-end tests depend on live systems, so they always run when asked, never "up to date".
    outputs.upToDateWhen { false }
    if (watch) {
        environment("QUARTET_HEADLESS", "false")
        environment("QUARTET_SLOWMO_MS", slowMo)
    }
    environment("QUARTET_ENV", System.getenv("QUARTET_ENV") ?: "$projectDir/$configuredEnvironment")
    testLogging {
        events("passed", "failed", "skipped")
        // The message says why a test failed (for example that no Android device is connected). The stack trace is noise.
        showExceptions = true
        showCauses = true
        showStackTraces = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    doFirst { delete(layout.buildDirectory.dir("quartet-report")) }
    finalizedBy("quartetReport")
}

// After every run, write build/quartet-report/report.html: results, what each step did, and a screenshot of each screen.
// Open it in a browser. It never fails the build.
tasks.register<JavaExec>("quartetReport") {
    description = "Writes build/quartet-report/report.html from the last test run."
    classpath = fileTree("$quartetHome/lib") { include("*.jar") }
    mainClass.set("io.quartetqe.generator.Main")
    args("report")
    workingDir = projectDir
    isIgnoreExitValue = true
    // Plain output: the one line that says where the report is.
    standardOutput = System.out
}
