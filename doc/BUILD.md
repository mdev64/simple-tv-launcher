# Build & install guide

How to build, sign, test and install Simple TV Launcher from source. To just install it, use
the APK from the [latest release](https://github.com/mdev64/simple-tv-launcher/releases/latest).

## 1. Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | platform 35, build-tools 34+ |
| adb | any recent platform-tools |

You don't need Android Studio: Gradle 8.9 comes with the wrapper (`./gradlew`).

Point Gradle at your JDK and SDK:

```sh
export JAVA_HOME=/path/to/jdk-17          # e.g. /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # your SDK path
```

`local.properties` is machine-specific and gitignored.

## 2. Debug build

```sh
./gradlew clean assembleDebug
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are signed with the standard Android debug key and run R8 too, so their size is
close to a release build's (about 900 KB; 430 KB of that is the built-in wallpapers).

## 3. Signed release build

### 3a. Create a signing key (once)

```sh
keytool -genkeypair -v \
  -keystore release-keystore.jks \
  -alias simpletvlauncher \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep this file and its passwords safe. Every update must be signed with the **same** key, or
Android refuses to install it over the old version.

### 3b. Tell Gradle about it

Create `keystore.properties` in the project root:

```properties
storeFile=../release-keystore.jks
storePassword=your-store-password
keyAlias=simpletvlauncher
keyPassword=your-key-password
```

`storeFile` is relative to the `app/` module, hence `../`.

Both `keystore.properties` and `*.jks` are in `.gitignore`. **Never commit them.** To check,
run:

```sh
git check-ignore -v keystore.properties release-keystore.jks
```

### 3c. Build

```sh
./gradlew clean assembleRelease
```

The release build turns on full R8 plus resource shrinking. Output:
`app/build/outputs/apk/release/app-release.apk`.

### 3d. Verify the signature

```sh
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Rename it for publishing:

```sh
cp app/build/outputs/apk/release/app-release.apk release-signed.apk
```

APKs are gitignored. Attach them to a GitHub release instead of committing them.

Before each release, bump `versionCode` (by +1) and `versionName` in `app/build.gradle.kts`.

## 4. Test

There are no automated tests. A release is checked with lint and on a real TV.

```sh
./gradlew lint            # report: app/build/reports/lint-results-debug.html
```

On the TV, check:

- [ ] Home opens, the clock shows, LEFT/RIGHT steps the carousel smoothly.
- [ ] OK opens an app; BACK returns to the launcher.
- [ ] Hold OK on a card → move / remove / hide work.
- [ ] DOWN opens Other apps; hold OK → *Add to home*; BACK closes it.
- [ ] UP reaches the top bar; Wi-Fi, VPN and both settings buttons open.
- [ ] Settings → wallpaper picker: built-in photos apply; your own photos after granting access.
- [ ] Screen saver → *Preview* works for each mode.
- [ ] Upgrade install (`install -r`) keeps pins, theme and hidden apps.

Check frame times while stepping the carousel:

```sh
adb shell dumpsys gfxinfo com.mdev64.simpletvlauncher reset
# press RIGHT a few times on the remote
adb shell dumpsys gfxinfo com.mdev64.simpletvlauncher | grep -A6 "50th percentile"
```

## 5. Install on the TV

On the TV, turn on **Developer options → USB / network debugging**, then:

```sh
adb connect <tv-ip>:5555
adb install -r release-signed.apk          # or app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mdev64.simpletvlauncher/.MainActivity
```

- Use `install -r` to upgrade. `adb uninstall com.mdev64.simpletvlauncher` deletes your pins,
  theme and hidden apps.
- A debug build and a release build are signed with different keys, so one can't upgrade the
  other. Uninstall first when switching between them.
- To make it the Home-button launcher, open **Settings → Default Launcher** in the app.

Useful while developing:

```sh
adb exec-out screencap -p > screen.png                   # screenshot
adb shell input keyevent KEYCODE_DPAD_RIGHT              # remote keys
adb logcat --pid=$(adb shell pidof com.mdev64.simpletvlauncher)
```