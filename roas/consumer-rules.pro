# ROASSensor SDK consumer ProGuard rules (applied to the host app's build).
# The Install Referrer library uses AIDL/reflection; keep it intact.
-keep class com.android.installreferrer.** { *; }
# GAID is read reflectively; keep the class names it looks up if Play Services
# is present (harmless if it isn't).
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient { *; }
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info { *; }

# The App Set ID is read reflectively too (AppSetId.kt), and these were MISSING —
# so R8 renamed every one of them and Class.forName threw ClassNotFoundException,
# which AppSetId catches and reports as "no App Set ID". Silent, and release-only:
# a debug build has no R8 and looks perfect.
#
# Confirmed rather than guessed, twice. play-services-appset ships no proguard.txt
# of its own, so nothing else was keeping them; the release mapping.txt showed
# AppSet -> k1.a, AppSetIdClient -> k1.b, AppSetIdInfo -> k1.c, Task -> g2.f,
# Tasks -> g2.i, while AdvertisingIdClient above mapped to itself — the same build
# proving both the failure and the fix. On-device, one handset back to back:
# debug reported app_set_id, the R8 release build reported nothing.
#
# Tasks/Task are here because AppSetId awaits the client's Task by name as well;
# keeping only the appset classes would move the failure one line down.
-keep class com.google.android.gms.appset.AppSet { *; }
-keep class com.google.android.gms.appset.AppSetIdClient { *; }
-keep class com.google.android.gms.appset.AppSetIdInfo { *; }
-keep class com.google.android.gms.tasks.Task { *; }
-keep class com.google.android.gms.tasks.Tasks { *; }
# The public SDK surface.
-keep class com.roassensor.sdk.Roas { *; }
-keep class com.roassensor.sdk.RoasEvent { *; }
