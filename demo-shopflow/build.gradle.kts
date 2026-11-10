plugins {
    application
}

dependencies {
    implementation(project(":quartet-core"))
    implementation(libs.kafka.clients)
    implementation(libs.mongo.driver)
    implementation(libs.postgres)
    implementation(libs.spring.kafka.test)
    implementation(libs.embedded.postgres)
    implementation(libs.flapdoodle.mongo)
    // PostgreSQL binaries for the machines this demo is likely to run on, including Linux CI agents.
    for (platform in listOf("darwin-arm64v8", "darwin-amd64", "linux-amd64", "linux-arm64v8")) {
        runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-$platform:17.5.0")
    }
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("demo.shopflow.ShopflowMain")
    applicationDefaultJvmArgs = listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}
