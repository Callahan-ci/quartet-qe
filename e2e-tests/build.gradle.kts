dependencies {
    testImplementation(project(":quartet-runtime"))
}

tasks.withType<Test> {
    maxParallelForks = 1
    doFirst { delete(layout.buildDirectory.dir("quartet-report")) }
}
