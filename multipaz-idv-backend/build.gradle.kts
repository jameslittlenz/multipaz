plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    ksp(project(":multipaz-cbor-rpc"))
    implementation(project(":multipaz"))
    implementation(project(":multipaz-idv"))
    implementation(project(":multipaz-openid4vci"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.bytestring)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":multipaz-server"))
    testImplementation(project(":multipaz-doctypes"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.java)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.test.host)
}
