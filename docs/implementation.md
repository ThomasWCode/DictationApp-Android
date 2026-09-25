# Implementation

## Layout

```
app/src/main/java/com/thomaswcode/dictationapp/
  DictationApp.kt              Application + AppGraph (the object graph; the Windows Generic Host)
  core/                        pure Kotlin port of DictationApp.Core
    Logger.kt
    audio/     AudioFrame, AudioCapture(+Factory), AudioSink(+Factory), WavFileSink, WavData, WavReplayAudioCapture
    cleanup/   CleanupLevel/Tone, SpokenCommandNormaliser, ListFormatter, OutputValidator, PromptBuilder,
               TextPostProcessor + Passthrough + Router, LlmPostProcessor (OkHttp)
    dictionary/ DictionaryTerm, KeytermsSelector, CorrectionDiffer
    history/   DictationRecord, RecordStatus, RetentionPolicy, HistoryRepository, CostEstimator
    insertion/ InsertMethod, ForegroundContext(+Provider), TextInserter, Clipboard, Notifier, InsertionTextFormatter
    rules/     AppRule, AppRulesResolver, DefaultAppRules (Android packages)
    session/   DictationStateMachine, DictationStatus, StatusHub, DictationOrchestrator
    settings/  AppSettings, JsonSettingsStore, SecretStore, SettingsApiKeyProvider, AppPaths
    transcription/ SessionOptions, Messages (+parser), TranscriptAssembler, StreamingTranscriber,
               AssemblyAiStreamingTranscriber (OkHttp WebSocket)
  platform/
    access/    DictationAccessibilityService, AccessibilityBridge (capture + insert), FieldInspector
    overlay/   BubbleView (Canvas), SnoozeTargetView, BubbleController (window, gestures, position)
    audio/     MicrophoneCapture (AudioRecord), InputDevices, WavPlayer
    AppLog, KeystoreSecretStore, SqliteHistoryRepository, AndroidServices (clipboard, notifier, retention)
  ui/          MainActivity + navigation, Home, History (+detail), Settings screens, rules/dictionary/correction,
               theme and shared components
app/src/debug/ DebugReceiver (adb hooks)
```

## Port of the core

Each Windows class became a Kotlin file with the same responsibilities and constants. Differences worth knowing:

- **Regexes** use `(?i)` only: Android's ICU engine rejects `(?U)` (its `\w` and `\b` are Unicode-aware
  already), and named groups are avoided; a unit test scans the sources for both. The "scratch that" marker is a
  private-use character rather than the word `SCRATCH`, so dictated text can never collide with it.
- **Settings** are an immutable `@Serializable data class`; `update { it.copy(...) }` replaces the Windows
  `Clone()` + mutate pattern. Writes are temp-file-then-rename; a corrupt file is renamed `.corrupt-<time>` and
  unknown keys and enum values fall back to defaults (`coerceInputValues`).
- **LLM client**: `withTimeout(8 s)` around the chain and OkHttp's `call.timeout()` for each 4 s attempt. A 401/403
  stops the chain; 429, other HTTP errors, timeouts and invalid output move on.
- **Transcriber**: OkHttp's WebSocket queues frames in order, so ForceEndpoint and Terminate always follow the
  last audio frame. The shutdown handshake is the same sequence and budgets.
- **Orchestrator**: a single-reader `Channel` of control events (Press, Release, HandsFree, Cancel); each session
  runs as a coroutine that `select`s on connect / release / cancel / fault / cap, exactly like the Windows
  `Task.WhenAny`. Helper jobs (connect, sender, silent-mic watcher, cap timer) live in a per-session supervisor
  that is cancelled when the session ends.

## Accessibility service

- Config: focus, click, selection and window events; `flagRetrieveInteractiveWindows` (to see the keyboard
  window), `flagReportViewIds` (browser address bars), `canRetrieveWindowContent`. No package filter.
- Every relevant event schedules an evaluation, debounced by 80 ms: the keyboard's top edge comes from the
  `TYPE_INPUT_METHOD` window's bounds, the field from `findFocus(FOCUS_INPUT)` (ignoring fields inside the keyboard
  itself; for Compose, whose host View reports the focus, the focused editable virtual node underneath), and
  `FieldInspector` rules out non-editable, password and numeric fields. Each change of decision is logged at debug
  level ("Bubble: eligible=…").
- `AccessibilityBridge` is the orchestrator's `ForegroundContextProvider` and `TextInserter`. Capture records the
  package, app label, window title and the browser URL (address bar by view id, last value cached per browser).
  Insertion runs on the main thread: read the text and selection (treating hint text as empty), format the
  spacing, `ACTION_SET_TEXT` the spliced text, move the cursor with `ACTION_SET_SELECTION`, wait 80 ms and read
  back; if unchanged, clipboard + `ACTION_PASTE`; if that is refused, clipboard only.
- Placeholders reported as text: WhatsApp's empty fields hold one zero-width space and report their placeholder
  as the text, with no hint text and `isShowingHintText` false. `FieldInspector.showsPlaceholder` checks short,
  single-line text whose cursor is not at its end in fields that offer `ACTION_SET_SELECTION`: it moves the cursor
  to the end of the reported text, which a field refuses beyond its real text (TextView and Compose both do), and
  puts it back when accepted. A placeholder counts as an empty field and is pasted into rather than replaced.

## Bubble

- Two `TYPE_ACCESSIBILITY_OVERLAY` windows (bubble and hide target), `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN |
  FLAG_LAYOUT_NO_LIMITS`, cut-out mode ALWAYS, positioned in screen coordinates (the same space as the keyboard
  window bounds). Hidden means `GONE` + `FLAG_NOT_TOUCHABLE`, so it never blocks touches.
- `BubbleView` draws the circle, microphone glyph, level bars (square-root scaled peaks, smoothed, with a slight
  wobble so a steady voice still looks alive), the hands-free pill and outcome rings on a Canvas, invalidating
  every frame only while animating.
- `BubbleController` owns gesture handling (confirm-press timer, tap/hold threshold, slide-to-cancel, drag,
  snap animation, snooze drop target), visibility, idle shrink, opacity and haptics, and persists the side and
  height after a drag.

## Microphone

`AudioRecord` with `VOICE_RECOGNITION` at 16 kHz mono PCM16, which is AssemblyAI's format, so there is no
resampling. 100 ms frames are read on a max-priority thread. `stop()` joins the thread (≤400 ms) so the final
frame reaches the socket before the channel closes. A preferred input device can be set; Bluetooth headsets are
made the communication device for the duration of the dictation.

## Tests

`./gradlew testDebugUnitTest`: 114 tests (see decisions.md). Robolectric runs the history repository against
Android's SQLite with FTS4 and exercises the retention pass on real files.
