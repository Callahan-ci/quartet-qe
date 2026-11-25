subprojects {
    group = "io.quartetqe"
    // A release is built with -PreleaseVersion=1.2.3 (the release workflow does this from the tag).
    version = (rootProject.findProperty("releaseVersion") as String?) ?: "0.2.0"

    apply(plugin = "java-library")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("passed", "failed", "skipped") }
    }

    val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        "testImplementation"(catalog.findLibrary("junit-jupiter").get())
        "testImplementation"(catalog.findLibrary("assertj").get())
        "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
    }
}
