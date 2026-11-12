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

// Helper scripts installed with the tool, so tests repos run them as ./quartet-qe get-started, secret and ai-key.
distributions {
    main {
        contents {
            from(rootProject.file("get-started.sh")) { into("scripts") }
            from(rootProject.file("set-secret.sh")) { into("scripts") }
            from(rootProject.file("set-ai-key.sh")) { into("scripts") }
        }
    }
}
