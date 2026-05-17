plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "me.thimmaiah.effectivebrowser"
    // The structured `release(N) { minorApiLevel = X }` form is the
    // AGP 9.0+ way of declaring a compileSdk minor (e.g. 36.1). The
    // shorter `compileSdk = 36` shorthand works for major-only targets
    // but doesn't let us opt into 36.1 APIs. Our AGP version is 9.1.0
    // (see gradle/libs.versions.toml) so the form is supported.
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.thimmaiah.effectivebrowser"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Debug: minify off (faster build, readable stacks for `adb logcat`).
        // No signing config; defaults to the AGP-generated debug keystore.
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            // Renames the package on-device so a debug and a release install
            // can coexist without uninstalling either side. Comment out if
            // you don't want this.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // Release: R8 enabled. We unconditionally ship optimized + shrunk +
        // obfuscated code so a reverse-engineer of the APK can't trivially
        // recover the source. proguard-rules.pro carries the app-specific
        // keep rules; default `proguard-android-optimize.txt` covers
        // Android-framework and Kotlin reflection edge cases.
        //
        // Note: no signing config is wired here, so `assembleRelease`
        // emits `app-release-unsigned.apk` (it is NOT debug-signed —
        // only the `debug` buildType uses the auto-generated debug
        // keystore). For Play distribution, add a
        // `signingConfigs { release { ... } }` block with a keystore
        // sourced from `~/.gradle/gradle.properties` (never check the
        // keystore into the repo) and reference it here.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath. Without this, the stubbed
    // org.json in the unit-test android.jar throws "Stub!" the moment
    // BrowserBlockerTest exercises the JSON-backed block list loader.
    // org.json is already available to main code at runtime (it ships in
    // the device framework), so this is a test-only dependency.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
