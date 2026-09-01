# APK install notes

The GitHub Actions artifact named `sms-gateway-debug-apk` contains `app-debug.apk`.
That APK is signed with Android's debug key and can be sideloaded for testing.

Do not install or share `app-release-unsigned.apk`. Android rejects unsigned release
APKs with errors such as "app not installed" or "package appears to be invalid".

For a production-style installable release APK, add these GitHub repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON`

Then download the `sms-gateway-release-apk` artifact and install `app-release.apk`.

When transferring by WhatsApp, send the actual `.apk` file as a document. If you
downloaded a GitHub Actions artifact, unzip it first; the artifact download itself
is a `.zip`, not the APK.
