plugins {
    application
}

dependencies {
    implementation(project(":quartet-runtime"))
}

application {
    mainClass.set("io.quartetqe.generator.Main")
    applicationName = "quartet-qe"
}

// Quartet's own principles and playbooks ship inside the tool, so a tests repo does not carry a copy of them.
// A repo only holds what it changes.
val prepareDefaults = tasks.register<Copy>("prepareDefaults") {
    from(rootProject.file("context/engineering-principles")) { into("defaults/context/engineering-principles") }
    from(rootProject.file("context/playbooks")) { into("defaults/context/playbooks") }
    into(layout.buildDirectory.dir("generated/defaults"))
    doLast {
        val base = layout.buildDirectory.dir("generated/defaults/defaults/context").get().asFile
        val files = base.walkTopDown().filter { it.isFile && it.name != "index.txt" }
            .map { it.relativeTo(base).invariantSeparatorsPath }.sorted().toList()
        File(base, "index.txt").writeText(files.joinToString("\n") + "\n")
    }
}

// Files a tests repo gets only when it asks for them: a Jenkinsfile and a connectors.yaml.
tasks.named<ProcessResources>("processResources") {
    from(prepareDefaults)
    from(rootProject.file("templates")) { into("templates") }
    from(rootProject.file("connectors.yaml")) { into("templates") }
}

// The version is readable at run time, so the MCP handshake and `quartet-qe version` report the real release.
tasks.jar {
    manifest { attributes("Implementation-Title" to "quartet-qe", "Implementation-Version" to project.version) }
}

// Tells a release install where it came from, so the tests repos it creates pin CI to this exact release.
val releaseProperties = tasks.register<WriteProperties>("releaseProperties") {
    destinationFile.set(layout.buildDirectory.file("generated/release/release.properties"))
    property("repoUrl", "https://github.com/goughliam1813/quartet-qe")
    property("version", project.version.toString())
}

// Helper scripts installed with the tool, so tests repos run them as ./quartet-qe get-started, secret and ai-key.
distributions {
    main {
        contents {
            from(rootProject.file("get-started.sh")) { into("scripts") }
            from(rootProject.file("set-secret.sh")) { into("scripts") }
            from(rootProject.file("set-ai-key.sh")) { into("scripts") }
            // What a release needs to create a tests repo without a clone of this repo.
            from(rootProject.file("new-tests-repo.sh")) { into("scripts") }
            from(rootProject.file("skeleton")) { into("skeleton") }
            // Every Gradle project carries its wrapper, so a new tests repo gets one from here.
            from(rootProject.file("gradlew")) { filePermissions { unix("rwxr-xr-x") } }
            from(rootProject.file("gradlew.bat"))
            from(rootProject.file("gradle/wrapper")) { into("gradle/wrapper") }
            from(releaseProperties)
            from(rootProject.file("LICENSE"))
            from(rootProject.file("SECURITY.md"))
        }
    }
}

// The one-click chat-app extension: the installed tool plus its manifest and icon, as one .mcpb file (a zip).
// Playwright's driver jar carries a Node runtime for every platform (about 200 MB). Each bundle keeps only its own,
// so a download is the size of one platform. Names are Playwright's platform folders.
val bundlePlatforms = mapOf(
    "mac-arm64" to "darwin", "mac" to "darwin", "linux" to "linux", "linux-arm64" to "linux",
)
val playwrightDrivers = listOf("linux", "linux-arm64", "mac", "mac-arm64", "win32_x64")
val driverJar = provider { configurations.runtimeClasspath.get().files.first { it.name.startsWith("driver-bundle-") } }

for ((platform, os) in bundlePlatforms) {
    val id = platform.replace("-", "_")
    val slimDriver = tasks.register<Zip>("slimDriver_$id") {
        archiveFileName.set(driverJar.map { it.name })
        destinationDirectory.set(layout.buildDirectory.dir("generated/driver/$platform"))
        from(driverJar.map { zipTree(it) }) {
            playwrightDrivers.filter { it != platform }.forEach { exclude("driver/$it/**") }
        }
    }
    val manifest = tasks.register<Copy>("mcpbManifest_$id") {
        from(rootProject.file("mcpb/manifest.json"))
        into(layout.buildDirectory.dir("generated/mcpb/$platform"))
        filter { line -> line.replace("__VERSION__", project.version.toString()).replace("\"darwin\", \"linux\"", "\"$os\"") }
    }
    tasks.register<Zip>("mcpbBundle_$id") {
        dependsOn(tasks.installDist)
        archiveFileName.set("quartet-qe-${project.version}-$platform.mcpb")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        from(layout.buildDirectory.dir("install/quartet-qe")) { exclude("lib/driver-bundle-*.jar") }
        // Scripts must stay runnable once unpacked; a plain directory copy does not carry that over.
        eachFile {
            val runnable = (path.startsWith("bin/") && !path.endsWith(".bat")) || (path.startsWith("scripts/") && path.endsWith(".sh")) ||
                path == "gradlew" || path == "skeleton/quartet-qe"
            permissions { unix(if (runnable) "rwxr-xr-x" else "rw-r--r--") }
        }
        from(slimDriver) { into("lib") }
        from(manifest)
        from(rootProject.file("mcpb/icon.png"))
        from(rootProject.file("PRIVACY.md"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.register("mcpbBundles") { dependsOn(bundlePlatforms.keys.map { "mcpbBundle_${it.replace("-", "_")}" }) }
