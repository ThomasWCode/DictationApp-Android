# DictationApp for Android

Wispr Flow–style dictation for Android, ported from the [Windows DictationApp](https://github.com/ThomasWCode/DictationApp).
Tap into any text field and a small floating bubble appears at the edge of the screen, just above the keyboard. Tap
it (or hold it) and speak: your words are transcribed by AssemblyAI, cleaned up by Groq, and typed into the field.

- **The bubble**: a small circle that follows the keyboard, adjustable in size (50–160%) and idle opacity
  (20–100%). There is no full floating window on Android, only the bubble.
  - **Tap**: hands-free. The bubble becomes a pill with **✕** (discard), a live level meter, and **✓** (insert).
  - **Hold**: push-to-talk; release to insert. Slide your finger away before releasing to discard.
  - **Drag**: it snaps to the nearest edge and remembers its height above the keyboard. Drop it on the
    **Hide 10 min** target at the bottom to snooze it.
  - Red while listening, breathing blue while finishing, grey when offline, an amber ring when cleanup was skipped.
  - Hidden in password, PIN, number and phone fields, and in any app you list.
- **Same pipeline and settings as Windows**: AssemblyAI Universal-3.5 Pro streaming with the personal dictionary
  as key terms; Groq cleanup (None/Light/Medium/High) and tone (Neutral/Formal/Casual) with the same prompt, fallback
  chain and output validation; optional app rules per app package or website (none by default: every app follows your defaults until you
  add it, e.g. Gmail formal or WhatsApp casual).
- **History**: searchable, with audio playback, Copy, Insert, Retry (failed dictations), Undo AI edit, Delete,
  retention settings and a cost estimate. **Correct last dictation** turns your fixes into dictionary terms.

Docs: [features](docs/features.md) · [implementation](docs/implementation.md) · [decisions](docs/decisions.md) ·
[manual test checklist](docs/manual-test-checklist.md)

## Install (debug APK, personal use)

The app is built as a debug APK and is not signed for the Play Store.

1. Build it (below) or take `app-debug.apk` from a build, and install it:
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```
   Copying the APK to the phone and opening it also works (allow "Install unknown apps" for your file manager).
2. Open **DictationApp**. The Home screen lists what is left to set up:
   - **AssemblyAI key** (Settings › API & models). **Groq key** is optional but needed for cleanup and tone.
   - **Microphone**: tap Allow.
   - **Bubble (accessibility service)**: tap Turn on, choose **DictationApp bubble** and switch it on.
     If the APK was installed from a file (not with adb), Android 13+ blocks this as a *restricted setting*:
     open **App info**, tap **⋮ › Allow restricted settings**, then switch the service on.
   - **Keep running** (optional): exempts the app from battery optimisation, which Samsung phones need so the
     bubble is not shut down in the background.
3. Tap the **Try it** field on the Home screen, then tap the bubble and speak.

Android restarts the accessibility service after a reboot on its own, so there is no autostart setting.

## Build

Requirements: JDK 17 (Android Studio's bundled JDK 21 also works) and the Android SDK with platform 35.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
.\gradlew testDebugUnitTest      # 114 JVM/Robolectric tests
.\gradlew assembleDebug          # app\build\outputs\apk\debug\app-debug.apk
```

Debug builds are signed with the machine's `~/.android/debug.keystore`. An APK built on another machine (or by
CI) has a different signature, so uninstall the old app before installing it; that also deletes its settings
and history.

## Debug hooks (adb)

The debug build has an adb-only receiver (guarded by the `DUMP` permission, which only the adb shell holds):

```powershell
$pkg = "com.thomaswcode.dictationapp"
# Store keys without typing them on the phone
adb shell am broadcast -n $pkg/.debug.DebugReceiver -a $pkg.debug.SET_KEYS --es assemblyai $env:ASSEMBLYAI_API_KEY --es groq $env:GROQ_API_KEY
# Push a 16-bit WAV the app can read
adb push sample.wav /sdcard/Android/data/$pkg/files/sample.wav
# Stream it and log every turn (see Settings › About › Share today's log, or logcat -s DictationApp)
adb shell am broadcast -n $pkg/.debug.DebugReceiver -a $pkg.debug.STREAM_TEST --es path /sdcard/Android/data/$pkg/files/sample.wav
# Full dictation into whatever field has focus after 5 s (the Windows --simulate)
adb shell am broadcast -n $pkg/.debug.DebugReceiver -a $pkg.debug.SIMULATE --es path /sdcard/Android/data/$pkg/files/sample.wav --ei delay 5
# Log the focused field's structure to logcat (add --ez text true for its text, --eia probe 1,5 to test cursor moves)
adb shell am broadcast -n $pkg/.debug.DebugReceiver -a $pkg.debug.DUMP_FOCUS
```

## Layout

```
app/src/main/java/com/thomaswcode/dictationapp/
  core/        pure Kotlin, no android.*: the port of DictationApp.Core (cleanup, transcription, dictionary,
               rules, history model, settings, session orchestrator)
  platform/    Android adapters: accessibility service + bridge, bubble overlay, AudioRecord capture,
               SQLite/FTS4 history, Keystore secrets, logging
  ui/          Jetpack Compose app: Home, History, Settings
app/src/debug/ adb test hooks
app/src/test/  JUnit + Robolectric tests (ported from the Windows test suite)
```
