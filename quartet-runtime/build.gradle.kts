dependencies {
    api(project(":quartet-core"))
    api(libs.junit.jupiter)
    api(libs.assertj)
    implementation(libs.playwright)
    implementation(libs.kafka.clients)
    implementation(libs.mongo.driver)
    implementation(libs.postgres)
    implementation(libs.jsonschema)
    runtimeOnly(libs.slf4j.simple)
}
