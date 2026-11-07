# Building

Requirements: JDK 17 and the Android SDK (API 35).

```
cd android
./gradlew assembleRelease          # APK in app/build/outputs/apk/release
./gradlew recordRoborazziDebug     # screenshots in app/build/outputs/roborazzi
```

## Release signing

Without a key the release APK is signed with the test key in `android/app/debug.keystore`.
For a permanent key add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ORBIT_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ORBIT_KEYSTORE_PASSWORD` | keystore password |
| `ORBIT_KEY_ALIAS` | key alias |
| `ORBIT_KEY_PASSWORD` | key password |

Keep the same key forever: Android only installs updates signed with the same key.
