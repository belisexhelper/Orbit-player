# Room entiteti i DAO se generišu — zadrži ih
-keep class com.orbitplayer.data.db.** { *; }
# DeviceAdminReceiver se poziva iz sistema
-keep class com.orbitplayer.kiosk.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
