import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Fresh clone without a real config? Copy the committed placeholder so the app still
// builds (sign-in won't work until you drop your own google-services.json here).
// The real file is gitignored — see README.
file("google-services.json").takeIf { it.exists() }
    ?: file("google-services.placeholder.json").takeIf { it.exists() }?.copyTo(
        file("google-services.json"),
        overwrite = true,
    )

// --- Release signing --------------------------------------------------------------
// Credentials come from a gitignored `keystore.properties` at the repo root, and fall
// back to environment variables (that's the path CI takes — see
// .github/workflows/release.yml). If neither is present the release signing config is
// never created, so a fresh clone still builds debug with no errors and no setup;
// `assembleRelease` just produces an unsigned APK until the owner configures a key.
//
// keystore.properties keys: storeFile, storePassword, keyAlias, keyPassword.
// Env var equivalents:      THUMBTREK_KEYSTORE_FILE, THUMBTREK_KEYSTORE_PASSWORD,
//                           THUMBTREK_KEY_ALIAS, THUMBTREK_KEY_PASSWORD.
//
// Environment variables are tracked as configuration-cache inputs, but the properties
// file read is not — after editing keystore.properties, run once with
// --no-configuration-cache (or touch a build file) so the change is picked up.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

/** keystore.properties wins locally; the env var is the CI path. */
fun signingCredential(property: String, environmentVariable: String): String? =
    keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }

// Resolved against the repo root, so keystore.properties can just say
// `storeFile=thumbtrek-release.jks` without an absolute path.
val releaseKeystore = signingCredential("storeFile", "THUMBTREK_KEYSTORE_FILE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }
val releaseStorePassword = signingCredential("storePassword", "THUMBTREK_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingCredential("keyAlias", "THUMBTREK_KEY_ALIAS")
val releaseKeyPassword = signingCredential("keyPassword", "THUMBTREK_KEY_PASSWORD")

val releaseSigningConfigured: Boolean = releaseKeystore != null &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.thumbtrek.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thumbtrek.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0"
    }

    signingConfigs {
        // Only declared when credentials actually exist — an unconfigured clone must not
        // fail configuration just because it has no keystore.
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 covers the API 26 floor, v2/v3 are what modern Android verifies and
                // what the in-app updater's PackageInstaller path relies on.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 stays OFF deliberately. proguard-rules.pro already carries the keep rules
            // this app would need (Room, Firebase/Firestore, Compose, the accessibility
            // service, the updater's reflective-free surface), but nothing here has been
            // verified by an actual shrunk build yet — and a bad R8 config on a sideloaded,
            // self-updating app is unrecoverable: the broken build is also the one that
            // ships the next update. Flip both flags to true, run `assembleRelease`, and
            // smoke-test tracking + sign-in + leaderboard on a device before trusting it.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // null when unconfigured → same unsigned output as before, no build failure.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    // App Startup: lets the self-updater register its periodic check on process
    // start without a hook in ThumbTrekApp. See update/UpdateStartupInitializer.
    implementation(libs.startup.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.coroutines)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
}
