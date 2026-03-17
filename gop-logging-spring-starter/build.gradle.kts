plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-library`
}

dependencies {
    api(project(":gop-logging-contract"))
    implementation(project(":gop-logging-core"))
    implementation(project(":gop-logging-spring"))
    implementation(libs.slf4jApi)
    implementation(libs.springBootAutoconfigure)
    implementation(libs.springWeb)

    testImplementation(libs.springTest)
    testImplementation(kotlin("test"))
}
