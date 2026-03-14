plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":gop-logging-contract"))
    implementation(libs.slf4jApi)
    implementation(libs.jacksonDatabind)
    testImplementation(kotlin("test"))
}
