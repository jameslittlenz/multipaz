import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.skie)
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

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
    // round trip against the real issuer code running in-process, can be tested with `jvmTest`.
    jvm()

    applyDefaultHierarchyTemplate()

    // iOS: the ValidatopiaShared framework consumed by wallet-ios and verifier-ios, which compile
    // multipaz-swiftui from source like samples/SwiftTestApp does. It exports multipaz itself, so
    // the apps import only this one Kotlin framework.
    val xcFrameworkName = "ValidatopiaShared"
    val xcf = XCFramework(xcFrameworkName)
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        val platform = when (it.name) {
            "iosX64" -> "iphonesimulator"
            "iosArm64" -> "iphoneos"
            "iosSimulatorArm64" -> "iphonesimulator"
            else -> error("Unsupported target ${it.name}")
        }
        it.binaries.all {
            linkerOpts(
                "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/${platform}/",
                "-Wl,-rpath,/usr/lib/swift",
                "-lsqlite3"
            )
        }
        it.binaries.framework {
            export(project(":multipaz"))
            export(project(":multipaz-doctypes"))
            export(project(":multipaz-idv"))
            // The Gym Membership is a multipaz-utopia loyalty card, and multipaz-swiftui's consent
            // sheet renders multipaz-utopia's test "ping" transaction.
            export(project(":multipaz-utopia"))
            export(libs.kotlinx.io.bytestring)
            export(libs.kotlinx.io.core)
            export(libs.kotlinx.datetime)
            export(libs.kotlinx.coroutines.core)
            export(libs.kotlinx.serialization.json)
            export(libs.ktor.client.core)
            export(libs.ktor.client.darwin)
            baseName = xcFrameworkName
            binaryOption("bundleId", "org.multipaz.samples.validatopia.$xcFrameworkName")
            binaryOption("bundleVersion", projectVersionCode.toString())
            binaryOption("bundleShortVersionString", projectVersionName)
            freeCompilerArgs += listOf(
                // Minimum iOS version 26.0, matching multipaz-swiftui's consumers.
                "-Xoverride-konan-properties=" +
                    "osVersionMin.ios_arm64=26.0;" +
                    "osVersionMin.ios_simulator_arm64=26.0;" +
                    "osVersionMin.ios_x64=26.0",
            )
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":multipaz"))
                api(project(":multipaz-doctypes"))
                api(project(":multipaz-idv"))
                // The Gym Membership is a multipaz-utopia loyalty card.
                api(project(":multipaz-utopia"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.io.bytestring)
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.client.core)
            }
        }

        val iosMain by getting {
            dependencies {
                api(libs.ktor.client.darwin)
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

// ValidatopiaRoundTripTest drives a real issuer. On the JVM it runs in-process; the issuer is
// JVM-only, so for the iOS simulator tests this starts MainValidatopia on localhost (which the
// simulator shares) and hands its URL to the test binary. The build service stops the issuer when
// the build finishes, whether or not the tests passed.
abstract class ValidatopiaTestIssuer : BuildService<ValidatopiaTestIssuer.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val javaExecutable: RegularFileProperty
        val classpath: ConfigurableFileCollection
        val repoRoot: DirectoryProperty
        val logFile: RegularFileProperty
    }

    private var process: Process? = null
    private var url: String? = null

    @Synchronized
    fun start(): String {
        url?.let { return it }
        val port = ServerSocket(0).use { it.localPort }
        val baseUrl = "http://localhost:$port"
        val root = parameters.repoRoot.get().asFile
        val log = parameters.logFile.get().asFile.apply { parentFile.mkdirs() }
        process = ProcessBuilder(
            parameters.javaExecutable.get().asFile.absolutePath,
            "-cp", parameters.classpath.asPath,
            "org.multipaz.openid4vci.server.MainValidatopia",
            "-param", "base_url=$baseUrl",
            "-param", "server_host=127.0.0.1",
            "-param", "server_port=$port",
            "-param", "database_engine=ephemeral",
            "-config", "$root/multipaz-server-deployment/validatopia-test-keys/validatopia-keys.conf",
            "-param", "personas_seed_dir=$root/multipaz-server-deployment/docker/init/personas",
            "-param", "admin_bootstrap_pass=test-only-${UUID.randomUUID()}",
        ).redirectErrorStream(true).redirectOutput(log).start()

        val deadline = System.currentTimeMillis() + 120_000
        while (true) {
            check(process!!.isAlive) { "The test issuer exited early; see $log" }
            val up = try {
                val connection = URI("$baseUrl/.well-known/openid-credential-issuer").toURL()
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 1_000
                connection.responseCode == 200
            } catch (e: IOException) {
                false
            }
            if (up) break
            check(System.currentTimeMillis() < deadline) { "The test issuer didn't start in time; see $log" }
            Thread.sleep(500)
        }
        url = baseUrl
        return baseUrl
    }

    override fun close() {
        process?.destroy()
        process?.waitFor(10, TimeUnit.SECONDS)
        process?.destroyForcibly()
    }
}

val testIssuerServerProject = project(":multipaz-openid4vci-server")
val testIssuer = gradle.sharedServices.registerIfAbsent("validatopiaTestIssuer", ValidatopiaTestIssuer::class) {
    parameters.javaExecutable.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.map { it.executablePath }
    )
    parameters.classpath.from(
        testIssuerServerProject.layout.buildDirectory.dir("install/multipaz-openid4vci-server/lib").map {
            it.asFileTree.matching { include("*.jar") }
        }
    )
    parameters.repoRoot.set(rootProject.layout.projectDirectory)
    parameters.logFile.set(layout.buildDirectory.file("validatopia-test-issuer.log"))
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(":multipaz-openid4vci-server:installDist")
    usesService(testIssuer)
    doFirst {
        // simctl forwards only SIMCTL_CHILD_-prefixed variables to the test process.
        environment("SIMCTL_CHILD_VALIDATOPIA_TEST_ISSUER_URL", testIssuer.get().start())
    }
}
