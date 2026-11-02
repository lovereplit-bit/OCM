plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionName = (findProperty("appVersionName") as String?) ?: "2.0.0"
val appVersionCode = ((findProperty("appVersionCode") as String?) ?: "29887640").toInt()
val ksPass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "opencodemob"
val releaseKs = rootProject.file("keystore/release.jks")

android {
    namespace = "app.ocmobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ocmobile"
        minSdk = 26
        // 28 on purpose: proot runs binaries from the app data directory,
        // and with targetSdk >= 29 Android forbids exec from app_data_file (same as Termux).
        // The APK is distributed as a file, not through Play.
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += "arm64-v8a" }
    }

    // targetSdk 28: see above; not an error for an APK outside Play
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += listOf("ExpiredTargetSdkVersion", "MissingTranslation")
    }

    signingConfigs {
        create("release") {
            if (releaseKs.exists()) {
                storeFile = releaseKs
                storePassword = ksPass
                keyAlias = "opencodemob"
                keyPassword = ksPass
            } else {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        // proot and its libraries ship as lib*.so and must be extracted to disk,
        // otherwise they can't be run as executables.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.gms.code.scanner)
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
}
