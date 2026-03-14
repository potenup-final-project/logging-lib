plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":gop-logging-contract"))
    implementation(project(":gop-logging-core"))
    implementation(libs.slf4jApi)
    implementation(libs.jacksonDatabind)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.springBootAutoconfigure)
    implementation(libs.springContext)
    implementation(libs.springAop)
    implementation(libs.springWeb)
    implementation(libs.aspectjWeaver)
    compileOnly(libs.jakartaServletApi)
    testImplementation(libs.jakartaServletApi)
    testImplementation(libs.springTest)
    testImplementation(kotlin("test"))
}
