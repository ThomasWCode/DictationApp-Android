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
| Insertion method | Splice at the cursor with `ACTION_SET_TEXT` and verify, paste as fallback; web content always pastes; Gmail, Outlook, Word and Docs paste by rule | Android cannot restore the clipboard afterwards (background reads are blocked), so the direct method avoids clobbering it. Replacing the whole text of a rich editor drops its formatting, hence Paste for those apps; `SET_TEXT` on web inputs may not behave as a real edit, hence paste there. |
| Spacing | Read the character before and after the cursor | Unlike Windows, Android exposes the field's text and selection, which is more reliable than remembering the last insertion. The Windows memory is still used where the text is unreadable. |
| When the bubble shows | Editable field + keyboard open, not secret/numeric, not in a hidden app | Wispr Flow's rules. Numeric and phone fields are excluded because dictation makes no sense there. |
| Opacity | Applies while idle; fully opaque while dictating | A faint bubble is fine to glance past, but the recording state must be unmistakable. |
| Offline | Grey bubble; a dictation fails fast with a toast | Matches Wispr Flow; the Windows Retry path handles recordings saved after a mid-dictation network failure. |
| Remember style changes | Dropped | It needs the Flow bar chips or arrow keys, which do not exist here; defaults and app rules cover the same need. |
| Retention and orphan sweep | Startup + hourly, orphans older than 30 minutes | Same schedule as Windows; the grace period protects a WAV that is being recorded. |
| Race between a new press and a finishing session | The idle hand-over and a new press are serialised by a lock; a session only returns the orchestrator to Idle if it still owns it | On Windows the old session's clean-up could, in principle, publish Idle over a session that had just started; closing that window was cheap. |

## Testing notes

- 99 JVM tests: the Windows Core test cases ported (normaliser, lists, validator, prompt, assembler, protocol
  parsing, session options, rules, keyterms, differ, formatter, cost, settings store, WAV I/O, state machine),
  the LLM client against MockWebServer (fallback chain, 401 stop, validation, per-attempt and total timeouts),
  fourteen orchestrator scenarios with in-memory fakes, and the SQLite/FTS4 history under Robolectric.
- The accessibility, overlay and microphone code needs a device; see `manual-test-checklist.md`.
