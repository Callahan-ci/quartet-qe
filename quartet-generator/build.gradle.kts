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

// The skeleton's Jenkinsfile is the one source. setup-jenkins writes it when a repo has none.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("skeleton")) {
        include("Jenkinsfile")
        into("skeleton")
    }
}
