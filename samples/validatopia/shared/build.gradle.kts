import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Host-only target so the shared (non-UI) logic, including the issuance-to-verification
    // round trip against the real issuer code, can be tested with `jvmTest`. The iOS targets
    // arrive with milestone M5.
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":multipaz"))
                api(project(":multipaz-doctypes"))
                api(project(":multipaz-idv"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.bytestring)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(project(":multipaz-openid4vci"))
                implementation(project(":multipaz-idv-backend"))
                implementation(project(":multipaz-server"))
                implementation(libs.ktor.client.java)
                implementation(libs.ktor.server.test.host)
                implementation(libs.ktor.server.netty)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
            }
        }
    }
}

// Compose (the Android theme) is Android-only here; the jvm() target is plain Kotlin for tests.
composeCompiler {
    targetKotlinPlatforms.set(setOf(KotlinPlatformType.androidJvm))
}

android {
    namespace = "org.multipaz.samples.validatopia.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
