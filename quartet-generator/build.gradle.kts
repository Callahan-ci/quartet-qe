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
