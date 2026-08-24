import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.detekt)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
}

val googleServicesConfigPresent = listOf(
  "google-services.json",
  "src/debug/google-services.json",
  "src/release/google-services.json"
).any { file(it).isFile }

android {
  namespace = "com.example"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.aistudio.vvfsmartmanager.app"
    minSdk = 24
    targetSdk = 37
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
    versionName = project.findProperty("versionName") as String? ?: "1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // Cloud transfer is disabled by default. A distribution that has completed OAuth
    // provisioning must explicitly override this build config through a controlled release.
    buildConfigField("boolean", "CLOUD_SYNC_ENABLED", "false")
    // Validation builds may intentionally omit google-services.json. The runtime auth
    // boundary rejects this empty fallback; configured builds use only plugin output.
    if (!googleServicesConfigPresent) {
      resValue("string", "default_web_client_id", "\"\"")
    }
  }

  signingConfigs {
    val keystorePath = System.getenv("KEYSTORE_PATH")
    if (!keystorePath.isNullOrEmpty()) {
      create("release") {
        storeFile = file(keystorePath)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    val debugKeystoreFile = file("${rootDir}/debug.keystore")
    if (debugKeystoreFile.exists()) {
      create("debugConfig") {
        storeFile = debugKeystoreFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfigs.findByName("release")?.let { signingConfig = it }
    }
    debug {
      // Generate measurable unit and instrumentation coverage reports for CI.
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
      signingConfigs.findByName("debugConfig")?.let { signingConfig = it }
    }
  }
  lint {
    checkReleaseBuilds = true
    abortOnError = true
    lintConfig = file("lint.xml")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
    resValues = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.security.crypto)
  // Patch the Gson version brought by the legacy security-crypto migration reader.
  implementation(libs.gson)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation("net.zetetic:sqlcipher-android:4.18.0@aar")
  implementation("androidx.sqlite:sqlite:2.7.0")
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.litert)
  implementation(libs.mlkit.text.recognition)
  implementation(libs.mlkit.text.recognition.devanagari)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.crashlytics)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.mockk)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.androidx.arch.core.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  // Robolectric 4.16.1 resolves bcprov 1.81; keep the patched provider test-only.
  testImplementation(libs.bouncycastle.bcprov)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.work.testing)
  androidTestImplementation(libs.mockk.android)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.leakcanary.android)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

detekt {
  buildUponDefaultConfig = true
  allRules = false
  ignoreFailures = false
  config.setFrom(file("config/detekt/detekt.yml"))
  baseline = file("detekt-baseline.xml")
}

// Crashlytics mapping upload requires the Google Services-generated app ID file.
// Keep upload enabled when Firebase configuration is present, but do not make
// local or secret-free validation builds fail solely because telemetry is absent.
tasks.configureEach {
  if (name.startsWith("uploadCrashlyticsMappingFile")) {
    enabled = file("google-services.json").isFile
  }
}
