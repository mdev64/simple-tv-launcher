import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load keystore.properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.mdev64.simpletvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mdev64.simpletvlauncher"
        minSdk = 26
        // Note: with targetSdk >= 29, WifiManager.setWifiEnabled() is a no-op on Android 10+,
        // so the Wi-Fi pill opens the system Wi-Fi panel/settings on those TVs.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            storeFile = keystoreProperties["storeFile"]?.let { file(it as String) }
            storePassword = keystoreProperties["storePassword"] as String?
        }
    }

    buildTypes {
        debug {
            // R8 enabled on debug too, so the size you measure is real.
            // Fast mode: shrink + obfuscate, no resource shrinking (keeps incremental builds quick).
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            // Full R8: code shrinking + resource shrinking + obfuscation.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Strip everything we never ship: metadata, licenses, Kotlin stdlib leftovers.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/**/androidx/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "kotlin/**",
                "**.bin",
                "**.txt",
                "**.versions.json",
                "DebugProbesKt.bin",
                "mobile/README.txt"
            )
        }
        // No native libs in this app at all.
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        // QUERY_ALL_PACKAGES is intentional for a launcher.
        disable += "QueryAllPackagesPermission"
        checkReleaseBuilds = false
    }
}

dependencies {
    // The only runtime dependency: plain RecyclerView (no Leanback, no AppCompat, no Compose).
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
