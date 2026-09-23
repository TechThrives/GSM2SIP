import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.callagent.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.callagent.gateway"
        // Android 12.  Deliberately not lower: the codebase carried 22
        // Build.VERSION gates to serve Android 8-11, and 20 of them (91%)
        // were for APIs that exist from Android 10 up.  Raising the floor to
        // 31 lets all twenty be deleted outright instead of maintained — the
        // four copies of the root `--uid` / `autoRevoke` boilerplate in
        // CallOrchestrator, RtpSession, BootReceiver and GatewayService were
        // the worst of it.  Only two gates survive: the pre-Android 13
        // SubscriptionInfo/line1Number branch in OwnNumber.kt and the
        // POST_NOTIFICATIONS request, both of which are real API 33
        // additions with no earlier equivalent.  Trade-off: Android 8-11
        // devices and the Galaxy S4 Mini are excluded; the Redmi Note 7 Pro,
        // Poco X3 and Galaxy S10e are all unaffected.
        minSdk = 31
        targetSdk = 34
        versionCode = 425
        versionName = "1.2.0"
    }

    // A release build is signed with the same debug key the debug build uses.
    // That is deliberate: it keeps the signature identical, so a release APK can
    // replace a debug one inside the Magisk module without PackageManager
    // rejecting it for a signature mismatch.  The point of building release here
    // is not secrecy, it is `debuggable=false` — ART compiles a debuggable app in
    // a deoptimizable mode with much weaker inlining, which costs real CPU in the
    // per-frame audio loops.
    signingConfigs {
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
