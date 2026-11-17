import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional: keystore.properties (git-ignored) with storeFile/storePassword/keyAlias/keyPassword.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.scamguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scamguard"
        minSdk = 29          // CallScreeningService role API
        targetSdk = 35
        // 64-bit phones, 32-bit ARM phones, and the x86_64 emulator. A phone-only APK is smaller:
        //   ./gradlew assembleRelease -Pabis=arm64-v8a,armeabi-v7a
        val abis = ((project.findProperty("abis") as String?) ?: "arm64-v8a,armeabi-v7a,x86_64").split(",")
        ndk { abiFilters += abis }
        versionCode = 6
        versionName = "0.6"
        // Default judge endpoint. Emulator -> host machine is 10.0.2.2. Override: -PjudgeUrl=https://<proxy>/api/judge
        val judgeUrl = (project.findProperty("judgeUrl") as String?) ?: "http://10.0.2.2:8787/api/judge"
        buildConfigField("String", "JUDGE_URL", "\"$judgeUrl\"")
        // Phase 1 onboarding backend (Python, see backend/). Override: -Pphase1Url=https://<host>/v1/onboarding/profile
        val phase1Url = (project.findProperty("phase1Url") as String?) ?: "http://10.0.2.2:8000/v1/onboarding/profile"
        buildConfigField("String", "PHASE1_URL", "\"$phase1Url\"")
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += listOf("onnx", "wav") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device speech-to-text: sherpa-onnx (Apache-2.0). Prebuilt .so, no CMake. Fetched by downloadSpeechEngine.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// ---- On-device speech recognition: fetched once, git-ignored (too large for the repo) ----
// Engine: sherpa-onnx (Apache-2.0). Model: streaming Zipformer "Kroko" English 2025-08-06 (CC-BY-SA, Banafo).
// Chosen by measurement on real Indian-English speech; see eval/results.md.
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-2.0.1.aar").asFile
val speechModelDir = layout.projectDirectory.dir("src/main/assets/model-kroko").asFile
val speechFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

fun fetch(url: String, to: File) {
    logger.lifecycle("Downloading $url")
    to.parentFile.mkdirs()
    val tmp = File(to.parentFile, to.name + ".part")
    URI(url).toURL().openStream().buffered().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    if (!tmp.renameTo(to)) { tmp.copyTo(to, overwrite = true); tmp.delete() }
}

val downloadSpeechEngine by tasks.registering {
    description = "Downloads the sherpa-onnx Android library and the Kroko speech model if missing."
    val need = { !sherpaAar.exists() || speechFiles.any { !File(speechModelDir, it).exists() } }
    outputs.upToDateWhen { !need() }
    onlyIf { need() }
    doLast {
        if (!sherpaAar.exists()) fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar", sherpaAar)
        for (name in speechFiles) {
            val f = File(speechModelDir, name)
            if (!f.exists()) fetch("https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/$name", f)
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadSpeechEngine) }
