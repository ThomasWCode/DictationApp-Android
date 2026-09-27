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
  `Clone()` + mutate pattern. A write goes to a temp file that is synced to disk and then renamed over the old one
  in a single step (`Files.move` with `ATOMIC_MOVE`); a failed write keeps the settings as they were and reports to
  a toast. A corrupt file is renamed `.corrupt-<time>` and unknown keys and enum values fall back to defaults
  (`coerceInputValues`).
- **LLM client**: `withTimeout(8 s)` around the chain and OkHttp's `call.timeout()` for each 4 s attempt. Only a
  401 (the key) stops the chain; 403, 404, 429, other HTTP errors, timeouts, invalid output and a reply whose
  `finish_reason` is not `stop` move on. The record keeps every attempt's reason.
- **Transcriber**: OkHttp's WebSocket queues frames in order, so ForceEndpoint and Terminate always follow the
  last audio frame. The shutdown handshake is the same sequence and budgets.
- **Orchestrator**: a single-reader `Channel` of control events (Press, Release, HandsFree, Cancel, Interrupt);
  each session runs as a coroutine that `select`s on connect / release / cancel / fault / interrupt / cap, exactly
  like the Windows `Task.WhenAny`, and re-checks the discard after each select so it wins a tie with the release.
  The microphone starts before the field lookup; a release before `Begin` stops it and closes the frame channel at
  once. A discard is honoured only while recording; Interrupt (the service going away) saves a Failed record with
  the audio. The WAV is finished only after its record is saved, so a crash leaves an unfinished file that
  `InterruptedRecordings` repairs and lists at the next start. Live dictations and History's Insert go through one
  `deliver` step (rule, checks, toasts, a clipboard lock shared with Retry). Helper jobs (connect, sender,
  silent-mic watcher, cap timer) live in a per-session supervisor that is cancelled when the session ends; each
  dictation logs one line of phase times (no text).

## Accessibility service

- Config: focus, click, selection and window events; `flagRetrieveInteractiveWindows` (to see the keyboard
  window), `flagReportViewIds` (browser address bars), `canRetrieveWindowContent`, and `isAccessibilityTool`
  (Android 14+ hides views an app marks data-sensitive, such as Gmail's whole compose screen, from any other
  service). No package filter.
- Every relevant event schedules an evaluation, debounced by 80 ms: the keyboard's top edge comes from the
  `TYPE_INPUT_METHOD` window's bounds, the field from `findFocus(FOCUS_INPUT)` (ignoring fields inside the keyboard
  itself; for Compose, whose host View reports the focus, the focused editable virtual node underneath), and
  `FieldInspector` rules out non-editable, password and numeric fields. Each change of decision is logged at debug
  level ("Bubble: eligible=…").
- `AccessibilityBridge` is the orchestrator's `ForegroundContextProvider` and `TextInserter`. Capture records the
  package, app label, window title and the browser URL: the address bar by view id, focused window first,
  address-shaped text only; when the bar has scrolled away, the last address seen in the last three minutes
  (`UrlMemory`), which the service refreshes at every evaluation. Insertion runs on the main thread: refresh the
  node, read the text and selection (treating hint text as empty unless the field shows signs of real text),
  format the spacing, `ACTION_SET_TEXT` the spliced text, move the cursor with `ACTION_SET_SELECTION`, wait 80 ms
  and read back; if unchanged, clipboard + `ACTION_PASTE`; if that is refused, clipboard only. With no field
  focused, the field the dictation started in is used if still on screen, but never pasted into once it has lost
  focus.
- Placeholders reported as text: WhatsApp's empty fields report their placeholder as the text, with no hint text
  and `isShowingHintText` false. The chat box is an EditText that reports "Message" but none of the cursor actions
  TextView adds whenever it holds real text (`ACTION_SET_SELECTION`, movement granularities), and no cursor
  (`FieldInspector.looksLikeStandIn`). That shape alone is no proof, since a custom field with a real draft can look
  the same: `reportsTextWithoutCursor` also needs Android's own sign that the field is empty (a TextView offers the
  rendering-info extra but not the character-location extra while it holds no text: `platformReportsEmpty`) or
  WhatsApp's chat-box id. Such a proven-empty field, which refuses a paste (TextView pastes only at a cursor), gets
  the dictation through `ACTION_SET_TEXT`. An unproven one keeps its text, gets the dictation at its end, and the
  dictation is also copied (`InsertionResult.leftOnClipboard`, an "Inserted and copied" toast). `DUMP_FOCUS` logs the
  extras so the marker can be checked on a device. The search bar holds
  one zero-width space with the cursor after it: `FieldInspector.showsPlaceholder` checks short,
  single-line text whose cursor is not at its end in fields that offer `ACTION_SET_SELECTION`: it moves the cursor
  to the end of the reported text, which a field refuses beyond its real text (TextView and Compose both do), and
  puts it back when accepted. A field that does not report its cursor is only checked when the text would be
  spliced in directly (at the end, where a missing cursor counts as being), never before a paste, which must land
  at the field's own cursor. A placeholder counts as an empty field and is pasted into rather than replaced.

## Bubble

- Two `TYPE_ACCESSIBILITY_OVERLAY` windows (bubble and hide target), `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN |
  FLAG_LAYOUT_NO_LIMITS`, cut-out mode ALWAYS, positioned in screen coordinates (the same space as the keyboard
  window bounds). Hidden means `GONE` + `FLAG_NOT_TOUCHABLE`, so it never blocks touches.
- `BubbleView` draws the circle, microphone glyph, level bars (heights from `LevelMeter`, smoothed, with a slight
  wobble so a steady voice still looks alive), the hands-free pill and outcome rings on a Canvas, invalidating
  every frame only while animating.
- `LevelMeter` (in `core/audio`, fed each frame's peak by the orchestrator) scales the bars to the user's voice, in
  decibels: the noise is the third-quietest of the last 30 frames (3 s), a frame 10 dB above it is speech, and the
  average speaking level is a plain mean over the first 10 speech frames, then a moving average (8% per frame, each
  frame counted as at most 10 dB away, so a tap barely moves it). The bars are full 5 dB above the average speaking
  level and empty 6 dB above the noise, kept 12 to 25 dB below the speaking level: without the 12 dB limit, three
  seconds of speech without a quiet frame became the noise reading and emptied the bars mid-sentence. Frames at or
  below −80 dBFS are ignored, and the bars stay down until speech has been heard. Every step is relative, so the
  same speech shows the same bars at any microphone volume. The orchestrator keeps one meter for the microphone
  across dictations; a replayed test WAV gets a fresh one.
- `BubbleController` owns gesture handling (confirm-press timer, tap/hold threshold, slide-to-cancel, drag,
  snap animation, snooze drop target), visibility, idle shrink, opacity and haptics, and persists the side and
  height after a drag.

## Microphone

`AudioRecord` with `VOICE_RECOGNITION` at 16 kHz mono PCM16, which is AssemblyAI's format, so there is no
resampling. 100 ms frames are read on a max-priority thread; a short last read is zero-padded to a whole frame.
`stop()` joins the thread (≤400 ms) so the final frame reaches the socket before the channel closes. The chosen
input device is set as preferred; without one, the phone's own microphone is preferred whenever another input is
connected. A chosen Bluetooth headset is brought up by selecting its output with the same address as the
communication device (`BluetoothRoute`; Android 11: `startBluetoothSco`, state from the sticky SCO broadcast);
the phone's microphone records until the link is up, then the recording switches devices between frames; after
2.5 s without the link it stays on the phone's microphone and the capture factory toasts once per run.

## Tests

`./gradlew testDebugUnitTest` (see decisions.md for what is covered). Robolectric runs the history repository
against Android's SQLite with FTS4, exercises the retention pass on real files, and drives the insertion routes and
microphone selection with shadowed accessibility nodes and audio devices.
