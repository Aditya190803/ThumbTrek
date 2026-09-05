# ThumbTrek R8/ProGuard rules.
#
# NOT ACTIVE YET. app/build.gradle.kts keeps `isMinifyEnabled = false` on purpose: these
# rules are written but have never been proven by a real shrunk build, and this app is
# sideloaded and self-updating — if a shrunk release breaks, the broken build is also the
# one that has to ship its own replacement. Turn minification on only together with a
# device smoke test of: scroll tracking, dashboard numbers, Google Sign-In, the
# leaderboard, the widget, and an end-to-end self-update.

# --- Keep line numbers so a crash report from a sideloaded build is still readable ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Annotations and generic signatures — Room, Firestore and Compose all read them.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisible*Annotations

# --- Room ------------------------------------------------------------------------
# Room ships consumer rules in its AAR, but entities and the generated *_Impl classes are
# only reached by name at runtime, so pin them explicitly.
-keep class com.thumbtrek.app.data.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- Firebase / Firestore --------------------------------------------------------
# The social layer talks to Firestore in plain Maps rather than POJOs, so reflective
# deserialization is not on the critical path — but keep the model-ish classes anyway in
# case that ever changes, plus the usual Firebase/GMS noise suppression.
-keep class com.thumbtrek.app.social.** { *; }
-keepclassmembers class com.thumbtrek.app.social.** {
    <init>();
    public <fields>;
    public void set*(***);
    public *** get*();
}
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
# Credential Manager / Google ID token flow used by sign-in.
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**

# --- Compose ---------------------------------------------------------------------
# The Compose runtime ships its own rules; these cover the reflective bits R8 has
# historically tripped over (composers, previews, saved-state).
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**

# --- Accessibility service -------------------------------------------------------
# Instantiated by the system from the manifest name. AGP's manifest keep rules normally
# cover this, but it is the single most load-bearing class in the app — do not gamble.
-keep class com.thumbtrek.app.track.ScrollTrackerService { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# --- Manifest-referenced components ----------------------------------------------
-keep class com.thumbtrek.app.widget.** { *; }
-keep class com.thumbtrek.app.ThumbTrekApp { *; }
-keep class com.thumbtrek.app.MainActivity { *; }

# --- WorkManager -----------------------------------------------------------------
# Workers are constructed reflectively by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.thumbtrek.app.work.** { *; }

# --- Self-updater ----------------------------------------------------------------
# The update package parses JSON by hand (org.json, no reflection), but the broadcast
# receiver that PackageInstaller calls back into is resolved by name.
-keep class com.thumbtrek.app.update.** { *; }

# --- Kotlin coroutines / stdlib ---------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { public <methods>; }
