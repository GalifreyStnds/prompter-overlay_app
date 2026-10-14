# Prompter (Android overlay teleprompter)

Floating teleprompter window that sits over any app, including your camera app.
Proof of concept for personal use, structured so it can be taken to Google Play later.

## What it does

- Script editor with one saved script
- Floating window: drag by the top bar, resize by the bottom-right corner
- Play, Top, Slow, Fast, A-, A+, Close controls on the window
- Settings: font, text color, text size, scroll speed, window opacity, countdown, mirror text
- Touch-scroll the text by hand at any time. Playback continues from where you leave it.
- Screen stays on while the window is showing

Not included: voice-paced scrolling, cloud sync, Bluetooth remote, recording, PDF or Word import, ads.

## Build the APK

### Option A: GitHub builds it (no local setup)

1. Create a new GitHub repo and push this folder to the `main` branch.
2. Open the Actions tab and run "Build APK" (it also runs on every push).
3. Download the `prompter-debug-apk` artifact, unzip it, copy `app-debug.apk` to your phone, and install it.
   Android will ask you to allow installs from your file manager or browser. That is expected for a debug build.

### Option B: Android Studio

Open this folder, let Gradle sync, then Run on a connected phone or build a debug APK.

## First run on the phone

1. Open Prompter, paste a script, adjust settings.
2. Tap Start overlay. The first time, Android opens "Display over other apps". Allow it for Prompter, press Back, tap Start again.
3. The app moves to the background. Open your camera app. The script window floats over it.
4. Press Play on the window.

## Before submitting to Google Play

- Package name `us.jeremycombs.prompter` is a placeholder. Pick the final one now. It cannot change after publishing.
- Target API: Play raises its minimum target level every year. This project targets 36. Check the current requirement in Play Console before you submit.
- The overlay permission and the foreground service type `specialUse` both need written justification in Play Console. The core function of the app is the floating window, so state that plainly. Read the current policy text before you submit, because review of these two items is strict.
- Launcher icon is in place (adaptive icon). The Play Store listing icon is `store-assets/ic_launcher_512.png`. The notification icon still uses a system default.
- Release builds need your own signing key. Use Play App Signing. Do not commit a keystore.
- Add a privacy policy URL and complete the Data safety form. Today the app collects nothing and stores the script on the device only.
- Run the app on at least one Android 12 or later phone and one older one if you can.

## Ads later

- Put the ad banner in `adContainer` in `MainActivity.kt`. It is hidden and empty now.
- Do not show ads in the floating window. Google Play and AdMob policies restrict ads shown outside the app that serves them, and the overlay would also cover your camera app.
- Adding AdMob means: the Google Mobile Ads SDK dependency, an AdMob app ID in the manifest, user consent handling (Google UMP) for regions that require it, and updating the Data safety form and privacy policy.
- Consider keeping the overlay itself ad-free and selling a one-time unlock or subscription for extras (multiple scripts, import). Ads in an app people use while recording tend to hurt ratings.

## Tests

`./gradlew testDebugUnitTest` runs the scroll and window-clamp math tests. The service and activity are not covered by automated tests yet. Test those by hand on a device.
