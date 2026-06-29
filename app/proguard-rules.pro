# R8 / ProGuard rules for Ebors.
# `proguard-android-optimize.txt` (the inherited default) covers the
# Android framework, AndroidX, and Kotlin reflection plumbing — this
# file only carries app-specific keep rules.

# ---------------------------------------------------------------------
# Stack traces
# ---------------------------------------------------------------------
# Keep file / line metadata so crash reports stay actionable. We then
# rename SourceFile to a uniform placeholder so the obfuscated names
# don't leak the original Kotlin filenames; R8 mapping.txt is the
# authoritative way to deobfuscate.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------
# Data classes parsed from / serialised to JSON
# ---------------------------------------------------------------------
# These types are constructed via `org.json.JSONObject` reads inside
# *toJson / fromJson functions in the same package — no reflection is
# involved today, so R8 wouldn't strip them. The keeps below are
# future-proofing in case the JSON layer is ever swapped for one that
# does use reflection (Moshi, kotlinx.serialization, etc.).
-keep class me.thimmaiah.ebors.DownloadItem { *; }
-keep class me.thimmaiah.ebors.Bookmark { *; }
-keep class me.thimmaiah.ebors.HistoryEntry { *; }
-keep enum me.thimmaiah.ebors.DownloadStatus { *; }

# ---------------------------------------------------------------------
# WebView JavaScript interfaces
# ---------------------------------------------------------------------
# We don't expose any @JavascriptInterface today. If that changes,
# uncomment the rule below and replace the FQCN.
# -keepclassmembers class me.thimmaiah.ebors.MyJsInterface {
#     @android.webkit.JavascriptInterface <methods>;
# }

# ---------------------------------------------------------------------
# OkHttp
# ---------------------------------------------------------------------
# OkHttp ships its own consumer rules in the AAR; nothing needed here.

# ---------------------------------------------------------------------
# Material Components / AndroidX
# ---------------------------------------------------------------------
# Material library handles its own keeps via consumer rules.