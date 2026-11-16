import java.net.URI
import java.util.Properties
import java.util.zip.ZipInputStream

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
        versionCode = 3
        versionName = "0.3"
        // Default judge endpoint. Emulator -> host machine is 10.0.2.2. Override: -PjudgeUrl=https://<proxy>/api/judge
        val judgeUrl = (project.findProperty("judgeUrl") as String?) ?: "http://10.0.2.2:8787/api/judge"
        buildConfigField("String", "JUDGE_URL", "\"$judgeUrl\"")
        // Phase 1 onboarding backend (Python, see backend/). Override: -Pphase1Url=https://<host>/v1/onboarding/profile
        val phase1Url = (project.findProperty("phase1Url") as String?) ?: "http://10.0.2.2:8000/v1/onboarding/profile"
        buildConfigField("String", "PHASE1_URL", "\"$phase1Url\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
    androidResources { noCompress += listOf("mdl", "fst", "conf", "int", "mat", "dubm", "ie", "stats", "wav") }
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

    // On-device speech-to-text (Apache-2.0). Prebuilt .so, no CMake.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// ---- Vosk English model: fetched once into assets (git-ignored) ----
val voskModelDir = layout.projectDirectory.dir("src/main/assets/model-en-us")
val downloadVoskModel by tasks.registering {
    description = "Downloads vosk-model-small-en-us-0.15 (Apache-2.0) into app assets if missing."
    val out = voskModelDir.asFile
    outputs.upToDateWhen { File(out, "uuid").exists() }
    onlyIf { !File(out, "uuid").exists() }
    doLast {
        val url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        logger.lifecycle("Downloading $url")
        out.deleteRecursively(); out.mkdirs()
        ZipInputStream(URI(url).toURL().openStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")   // strip the top-level folder
                if (rel.isNotEmpty()) {
                    val f = File(out, rel)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
                e = zip.nextEntry
            }
        }
        // StorageService.unpack() reads this file to know when to re-extract.
        File(out, "uuid").writeText("vosk-model-small-en-us-0.15")
    }
}
tasks.named("preBuild") { dependsOn(downloadVoskModel) }
