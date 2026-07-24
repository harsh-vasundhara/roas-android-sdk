# ROASSensor SDK consumer ProGuard rules (applied to the host app's build).
# The Install Referrer library uses AIDL/reflection; keep it intact.
-keep class com.android.installreferrer.** { *; }
# GAID is read reflectively; keep the class names it looks up if Play Services
# is present (harmless if it isn't).
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient { *; }
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info { *; }
# The public SDK surface.
-keep class com.roassensor.sdk.Roas { *; }
-keep class com.roassensor.sdk.RoasEvent { *; }
