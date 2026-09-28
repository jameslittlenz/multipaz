plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
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
    implementation(project(":multipaz"))
    implementation(project(":multipaz-idv"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.bytestring)

    testImplementation(libs.junit)
}
