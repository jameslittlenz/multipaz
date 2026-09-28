import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

// The issuer the wallet talks to until the user changes it in Settings. The default suits an
// emulator or USB-connected phone with `adb reverse tcp:8000 tcp:8000` and the Validatopia
// container running locally with BASE_URL=http://localhost:8000.
val validatopiaIssuerUrl = (project.findProperty("validatopia.issuerUrl") as String?)
    ?: "http://localhost:8000/openid4vci"

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

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.biometrics)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.android)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.bytestring)
                implementation(project(":multipaz"))
                implementation(project(":multipaz-compose"))
                implementation(project(":multipaz-doctypes"))
                implementation(project(":samples:validatopia:shared"))
            }
        }
    }
}

android {
    namespace = "org.multipaz.samples.validatopia.wallet"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.multipaz.samples.validatopia.wallet"
        // multipaz-compose requires API 29.
        minSdk = 29
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = projectVersionCode
        versionName = projectVersionName
        buildConfigField("String", "DEFAULT_ISSUER_URL", "\"$validatopiaIssuerUrl\"")
    }

    buildTypes {
        getByName("debug") {
            // Debug builds sign wallet attestations in-app with the public development identity
            // (DevWalletBackend); release builds must go through the attested wallet back-end.
            buildConfigField("boolean", "USE_DEV_ATTESTATION", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "USE_DEV_ATTESTATION", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}
