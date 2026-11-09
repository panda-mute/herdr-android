# Releasing

## How releases are made

CI builds the APK on every push and pull request. Only the default branch publishes a release, tagged `v0.1.<run number>`, with `herdr-android.apk` attached. Other branches and pull requests leave the APK as the `herdr-android-<run number>` artifact on their Actions run.

The version code is the Actions run number, so every release installs over the one before.

## Signing key (once, for the repo owner)

Android installs an update only when it is signed with the same key as the installed app. Make the key once and keep a copy somewhere safe: losing it means everyone has to uninstall once (and restore their SSH key backup).

```sh
keytool -genkeypair -keystore herdr-release.jks -alias herdr \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Herdr for Android"
base64 -w0 herdr-release.jks > herdr-release.jks.b64
```

Add these secrets under **Settings → Secrets and variables → Actions**, or with `gh secret set NAME`:

| Secret | Value |
| --- | --- |
| `HERDR_KEYSTORE_BASE64` | the contents of `herdr-release.jks.b64` (`gh secret set HERDR_KEYSTORE_BASE64 < herdr-release.jks.b64`) |
| `HERDR_KEYSTORE_PASSWORD` | the keystore password |
| `HERDR_KEY_ALIAS` | `herdr` |
| `HERDR_KEY_PASSWORD` | optional: the key password, only if it differs from the keystore password |

Without these secrets the APK is signed with the debug key and the default branch publishes no release.

## Signing a local build

Local builds use the debug key. To sign one with the release key, set `HERDR_KEYSTORE_FILE`, `HERDR_KEYSTORE_PASSWORD` and `HERDR_KEY_ALIAS`, then:

```sh
./gradlew :app:assembleRelease
```

## Building updates on a machine

[Changing the app from the phone](GUIDE.md#change-the-app-from-the-phone) builds updates on one of your machines with [`herdr-app-build`](../host/herdr-app-build/README.md). To sign them with the release key, keep `HERDR_KEYSTORE_FILE` (the keystore's path on that machine), `HERDR_KEYSTORE_PASSWORD` and `HERDR_KEY_ALIAS` in herdr-secret (Settings › Secrets in Herdr, or `herdr-secret set NAME`). The build runs under `herdr-secret run`, so the values reach only Gradle, never a log, a chat or git.

Without them, the update is CI's release for that merge, downloaded on the machine. Either way the version code is the merge's Actions run number, so a local build and CI's release of the same merge are the same version.
