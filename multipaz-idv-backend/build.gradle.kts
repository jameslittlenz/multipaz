import java.net.URI
import java.security.MessageDigest

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
    implementation(libs.jj2000)
    implementation(libs.onnxruntime)

    testImplementation(project(":multipaz-server"))
    testImplementation(project(":multipaz-doctypes"))
    testImplementation(project(":multipaz-utopia"))
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

// The ONNX face models OnnxFaceMatcher uses, from the OpenCV model zoo at a fixed commit. The
// hashes must match FaceModel in src/main/kotlin/org/multipaz/idv/backend/face/FaceModels.kt,
// which checks them again when the server loads the models. The models are never committed.
val faceModelsCommit = "47534e27c9851bb1128ccc0102f1145e27f23f98"
val faceModels = mapOf(
    "face_detection_yunet/face_detection_yunet_2023mar.onnx" to
        "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    "face_recognition_sface/face_recognition_sface_2021dec.onnx" to
        "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
)
val faceModelsDir = layout.buildDirectory.dir("face-models")

tasks.register("downloadFaceModels") {
    group = "validatopia"
    description = "Downloads the YuNet and SFace face models, checking their SHA-256 hashes."
    outputs.dir(faceModelsDir)
    doLast {
        val dir = faceModelsDir.get().asFile
        dir.mkdirs()
        for ((path, sha256) in faceModels) {
            val file = File(dir, path.substringAfterLast('/'))
            fun hashOf(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }
            if (file.isFile && hashOf(file.readBytes()) == sha256) {
                continue
            }
            val url = "https://media.githubusercontent.com/media/opencv/opencv_zoo/$faceModelsCommit/models/$path"
            logger.lifecycle("Downloading $url")
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val actual = hashOf(bytes)
            if (actual != sha256) {
                throw GradleException("$url has SHA-256 $actual, expected $sha256")
            }
            file.writeBytes(bytes)
        }
    }
}

// OnnxFaceMatcherTest's model tests run only when the models have been downloaded
// (`downloadFaceModels`). They use the placeholder persona portraits as test faces.
tasks.withType<Test>().configureEach {
    systemProperty("faceModelsDir", faceModelsDir.get().asFile.absolutePath)
    systemProperty(
        "personasDir",
        rootProject.file("multipaz-server-deployment/docker/init/personas").absolutePath
    )
}
