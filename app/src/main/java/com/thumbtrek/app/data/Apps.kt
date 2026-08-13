package com.thumbtrek.app.data

/** The four feeds ThumbTrek measures (PRD §5.1). Order is the display order in Settings. */
val TRACKED_APPS: Map<String, String> = linkedMapOf(
    "com.instagram.android" to "Instagram",
    "com.google.android.youtube" to "YouTube",
    "com.twitter.android" to "X",
    "com.reddit.frontpage" to "Reddit",
)

fun appName(pkg: String): String = TRACKED_APPS[pkg] ?: pkg.substringAfterLast('.')
