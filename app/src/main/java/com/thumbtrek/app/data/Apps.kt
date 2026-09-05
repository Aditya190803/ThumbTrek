package com.thumbtrek.app.data

/**
 * The four feeds ThumbTrek measures out of the box (PRD §5.1). Order is the display
 * order in Settings. Users can track any other installed app on top of these — those
 * live in [Prefs.customApps] with a label captured at add time, so no PackageManager
 * lookup is ever needed on the display path.
 */
val TRACKED_APPS: Map<String, String> = linkedMapOf(
    "com.instagram.android" to "Instagram",
    "com.google.android.youtube" to "YouTube",
    // Still com.twitter.android after the rebrand — a package name can't change without
    // becoming a different Play listing, so "com.x.android" would silently track nothing.
    "com.twitter.android" to "X",
    "com.reddit.frontpage" to "Reddit",
)

/** Display name for any package: built-in first, then user-added labels. */
fun appName(pkg: String, customLabels: Map<String, String> = emptyMap()): String =
    TRACKED_APPS[pkg] ?: customLabels[pkg] ?: pkg.substringAfterLast('.')
