# Decisions

Choices made while porting DictationApp to Android (2026-09-25), with the reasoning.

## Architecture

| Decision | Why |
|---|---|
| Accessibility service + overlay bubble, not a keyboard (IME) | This is how Wispr Flow's Android app works: the bubble floats over your existing keyboard and needs no keyboard switch. One accessibility service provides everything the Windows app needed a keyboard hook, UI Automation, WASAPI and a paste engine for: focus and keyboard tracking, the overlay window, text insertion, and microphone capture. |
| `TYPE_ACCESSIBILITY_OVERLAY` window | Drawn by the accessibility service, so no "Display over other apps" permission is needed, and it sits above the keyboard. It is never focusable, so the text field keeps focus and the keyboard stays open while you tap the bubble. |
| Record inside the accessibility service, no foreground service | Android's audio-sharing rules treat accessibility services as a privileged capturer (they are bound by the system with while-in-use capabilities; Voice Access works the same way). A microphone foreground service would need a permanent notification and cannot be started from the background on Android 14+. |
| `core/` is pure Kotlin | A direct port of `DictationApp.Core`: the same classes, algorithms, constants and test cases, runnable as plain JVM tests. Only `platform/` touches Android APIs. |
| OkHttp for WebSocket and HTTP | Mature, queues sends in order on its own writer thread (so the Windows send lock is unnecessary), per-call timeouts for the 4 s / 8 s LLM budgets. |
| Coroutines in place of Tasks/Channels | `Channel`, `CompletableDeferred` and `select` map one-to-one onto the Windows orchestrator's `Channel`, `TaskCompletionSource` and `Task.WhenAny`. |
| SQLite FTS4 instead of FTS5 | Android's built-in SQLite always has FTS4; FTS5 is not guaranteed. External-content FTS4 with triggers gives the same prefix search. Prefix terms are quoted as `"isoni*"` (FTS4 syntax, not FTS5's `"isoni"*`). |
| Android Keystore AES-GCM instead of DPAPI | Same property as DPAPI: the stored key is useless outside this app install. |
| Compose for the app, a hand-drawn View for the bubble | The overlay lives in a service window, where Compose would need a manual lifecycle owner; a Canvas view is lighter and animates the level bars at display rate. |
| Debug APK with R8 | The debug build is the deliverable (personal use). R8 still runs on it, with obfuscation off, to drop about 50 MB of unused Material icons; stack traces stay readable. |
| minSdk 30 (Android 11) | Covers current phones and removes several compatibility branches (window metrics, cutout modes, predefined haptics). |

## Behaviour

| Question | Choice | Why |
|---|---|---|
| Tap vs hold | Both work all the time, like the Windows hold and double-tap. A press becomes a dictation after 150 ms without movement; a release before 300 ms is a tap (hands-free); later is a hold (insert on release). | 150 ms separates a press from the start of a drag, so dragging never opens the microphone. 300 ms is the Windows tap threshold. The first 150 ms of audio is not captured, but nobody speaks that quickly after touching the screen. |
| A short tap cancels on Windows | On Android a tap starts hands-free | Wispr Flow's tap mode. The state machine therefore has no short-tap cancel: Release in Arming always finalises. |
| Discarding | ✕ on the hands-free pill; slide away before releasing in hold mode | There is no Escape key; slide-to-cancel is the familiar voice-message gesture. |
| Insertion method | Splice at the cursor with `ACTION_SET_TEXT` and verify, paste as fallback; web content always pastes; an app rule can choose Paste (e.g. for Gmail, Word) | Android cannot restore the clipboard afterwards (background reads are blocked), so the direct method avoids clobbering it. Replacing the whole text of a rich editor drops its formatting, so Paste is the better per-app choice there; `SET_TEXT` on web inputs may not behave as a real edit, hence paste there. |
| Spacing | Read the character before and after the cursor | Unlike Windows, Android exposes the field's text and selection, which is more reliable than remembering the last insertion. The Windows memory is still used where the text is unreadable. |
| When the bubble shows | Editable field + keyboard open, not secret/numeric, not in a hidden app | Wispr Flow's rules. Numeric and phone fields are excluded because dictation makes no sense there. |
| Opacity | Applies while idle; fully opaque while dictating | A faint bubble is fine to glance past, but the recording state must be unmistakable. |
| Offline | Grey bubble; a dictation fails fast with a toast | Matches Wispr Flow; the Windows Retry path handles recordings saved after a mid-dictation network failure. |
| Remember style changes | Dropped | It needs the Flow bar chips or arrow keys, which do not exist here; defaults and app rules cover the same need. |
| Failed dictations in History | Saved when there is audio (Retry) or any words were heard (to copy); a failure with neither only toasts | Windows saved only failures with audio. With "Store audio" off that dropped the partial transcript, so the words heard are kept too (Codex review). |
| Minimum Android version quirks | Bluetooth mics use `setCommunicationDevice` on 12+ and the legacy SCO switch on 11; `lintDebug` checks NewApi | minSdk is 30 but the audio routing API is 31 (Codex review). |
| Retention and orphan sweep | Startup + hourly, orphans older than 30 minutes | Same schedule as Windows; the grace period protects a WAV that is being recorded. |
| Race between a new press and a finishing session | The idle hand-over and a new press are serialised by a lock; a session only returns the orchestrator to Idle if it still owns it | On Windows the old session's clean-up could, in principle, publish Idle over a session that had just started; closing that window was cheap. |

## Changes requested after the first delivery (2026-09-25)

| Request | Decision | Why |
|---|---|---|
| "Per-app settings should start empty, with all apps obeying the general settings; apps can then be added" | `appRules` defaults to an empty list. The 0.1.0 seed is kept as `LegacyAppRules` only for the schema-2 migration, which removes rules that are still seeds (same target, insertion method, hint and on/off state) and keeps everything else, then saves. The App rules screen explains the empty default; **Add app** opens the installed-app picker and starts the rule from the current defaults; **Add website** does the same for a host; "Reset to defaults" became **Remove all**. The Windows app got the same change. | The seeded rules decided tone, cleanup and insertion for Gmail, WhatsApp and others without the user choosing them. Consequence: Gmail, Outlook, Word and Docs now use the default insertion method (type directly), which can drop rich-text formatting there; adding those apps with Insertion = Paste restores the old behaviour, and the App rules screen says so. |

## Second Codex review (2026-09-25)

| Finding | Fix |
|---|---|
| A socket failure or typed error after the release let a possibly truncated transcript be inserted as a success. | The transcriber remembers the failure even while closing, a fault ends the handshake waits at once, and `shutdown` raises it when no Termination arrived; the session also re-checks its fault. Both save a Failed record with audio for Retry. Deferreds are now completed exceptionally, never cancelled, because awaiting a cancelled one threw `CancellationException` and ended the session silently (found while fixing this). |
| Disabling the accessibility service mid-dictation left the microphone running with no bubble. | The service discards an active dictation when it disconnects. |
| `ACTION_CANCEL` on a held bubble inserted the text. | It discards: a system-cancelled touch is not the user letting go. |
| Log files (kept 14 days) contained dictated text. | Text is logged by length only; Delete all history also deletes the logs. |
| Delete all could delete the WAV of a dictation in progress. | Files written within the last minute are spared. |
| The insertion method came from the app where dictation started, even when the text landed elsewhere; History's Insert ignored rules. | Both resolve the rule of the app the text lands in; the tone chosen at the start is kept. |
| A read blocked on a stalled microphone kept the recorder after stop. | `stop()` stops the `AudioRecord` to unblock it. |
| (Windows PR) The migration deleted seeded rules the user had customised. | Only rules that are still seeds are removed (see above). |

## Findings from on-device testing (Galaxy S24, Android 16, Gboard, 2026-09-25)

| Finding | Fix |
|---|---|
| The bubble never appeared in Jetpack Compose text fields: `findFocus(FOCUS_INPUT)` returns the host `AndroidComposeView` (a plain, non-editable `View`), not the text field. This affects every Compose app, DictationApp itself included. | When the input-focused node is not editable, search its subtree (up to 600 nodes) for a focused, editable virtual node. |
| The first real dictation crashed the process: Android's regex engine is ICU-based and rejects the `(?U)` flag that the JVM accepts, so every JVM test had passed. The `ExceptionInInitializerError` was an `Error`, not an `Exception`, so it escaped the session's error handling. | `(?i)` only (ICU's `\w` and `\b` are Unicode-aware already); numbered instead of named groups; a `CoroutineExceptionHandler` on the app scope; and a unit test that scans the sources for both constructs. |
| Dragging the bubble sideways from the screen edge also fired the system Back gesture, which closed the keyboard (and so hid the bubble). | `systemGestureExclusionRects` over the bubble. |
| The dark idle bubble was hard to see over dark apps. | A faint light rim. |

Verified on the device: the bubble appears above Gboard in a Compose field and in Chrome's address bar, and hides
when the keyboard closes; a full dictation (AssemblyAI connect 943 ms, handshake 982 ms, Groq gpt-oss-120b cleanup
692 ms) typed "So I think we should ship the new version on Tuesday. What do you think?" from "Um, so I think we
should... We should ship the new version on Monday, no? Tuesday. What do you think?"; tap → hands-free pill → ✕
discards; hold → release finalises ("Nothing heard" in a silent room); drag snaps to either edge and remembers its
height; drop on the target snoozes and "Show now" restores; History lists, shows details and plays audio. Chrome's
address bar took the text through the direct path (no clipboard).

## Testing notes

- 114 JVM tests: the Windows Core test cases ported (normaliser, lists, validator, prompt, assembler, protocol
  parsing, session options, rules, keyterms, differ, formatter, cost, settings store, WAV I/O, state machine),
  the LLM client against MockWebServer (fallback chain, 401 stop, validation, per-attempt and total timeouts),
  nineteen orchestrator scenarios with in-memory fakes, the SQLite/FTS4 history under Robolectric, and the
  Android regex-compatibility scan.
- The accessibility, overlay and microphone code needs a device; see `manual-test-checklist.md`.
