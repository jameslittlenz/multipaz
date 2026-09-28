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

// Regenerates the fixed Validatopia TEST PKI (see multipaz-server-deployment/validatopia-test-keys/README.md).
// Re-running replaces every key and invalidates all previously issued Photo IDs.
tasks.register<JavaExec>("generateValidatopiaTestKeys") {
    group = "validatopia"
    description = "Generates the fixed Validatopia TEST IACA, test CSCA/DS and reader keys."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.multipaz.idv.backend.keys.ValidatopiaTestKeysGenerator")
    args(
        rootProject.file("multipaz-server-deployment/validatopia-test-keys").absolutePath,
        rootProject.file(
            "samples/validatopia/shared/src/commonMain/kotlin/org/multipaz/samples/validatopia/shared/trust/ValidatopiaTestPki.kt"
        ).absolutePath,
    )
}
