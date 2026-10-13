import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing: create android/keystore.properties with
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds use the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.airpointer.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airpointer.remote"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "2.4"

        // Internet connections (native/airlink) are built for phones' ABIs. x86_64 is only for the emulator:
        // add it with ./gradlew assembleRelease -PwithEmulatorAbi
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            if (project.hasProperty("withEmulatorAbi")) abiFilters += "x86_64"
        }
    }

    packaging {
        // Compress the native library inside the APK (smaller download; Android extracts it on install).
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.containsKey("storeFile")) {
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
            signingConfig = if (keystoreProps.containsKey("storeFile")) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // AirPointer's license and the third-party notices, shown in Settings -> Open-source licenses.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
}

val copyLicenses by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE")) { rename { "LICENSE.txt" } }
    from(rootProject.file("../licenses/THIRD-PARTY-NOTICES.txt"))
    into(layout.buildDirectory.dir("generated/licenseAssets"))
}
tasks.named("preBuild") { dependsOn(copyLicenses) }

dependencies {
    // QR decoding for pairing (Apache-2.0, pure Java). The only library; the camera is plain Camera2.
    implementation("com.google.zxing:core:3.5.3")
}
