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
| Minimum Android version quirks | Bluetooth mics use `setCommunicationDevice` on 12+ (with the headset's output: see the sixth round) and the legacy SCO switch on 11; `lintDebug` checks NewApi | minSdk is 30 but the audio routing API is 31 (Codex review). |
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
| Disabling the accessibility service mid-dictation left the microphone running with no bubble. | The service discards an active dictation when it disconnects. (2026-09-26: it now keeps the dictation for Retry; see the sixth round.) |
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
| "Dictating into WhatsApp puts 'Message' in front of the text; the search bar adds 'Ask Meta AI or Search'" (user report). WhatsApp's empty chat box and search bar hold one zero-width space and report the placeholder as their text, with no hint text and `isShowingHintText` false, so the direct path spliced the dictation into the placeholder. A new adb `DUMP_FOCUS` hook showed it: 21 characters reported, cursor at 1, and moves to 2 or 21 refused. | Short, single-line text whose cursor is not at its end is checked by moving the cursor to the end of the reported text: real text accepts (and gets its cursor back), a stand-in refuses. A stand-in counts as an empty field and is **pasted** at the field's cursor instead of replaced: pasting never removes anything if the check is ever wrong, and it keeps WhatsApp's invisible character. The cost is that the clipboard holds the dictation. Verified in WhatsApp's search bar: the field read back as the zero-width space followed by the dictated sentence. |
| "Dictating straight into a WhatsApp chat box still puts 'Message' in front" (after the first fix; the user allowed testing in WhatsApp chats). `DUMP_FOCUS` in the user's own "Message yourself" chat: an EditText reporting "Message" with no hint, cursor -1, and neither `ACTION_SET_SELECTION` nor movement granularities, so the cursor check never ran. The first fix then recognised it but WhatsApp refused the paste: TextView pastes only at a cursor. | An EditText reporting short text without the cursor actions TextView adds whenever it holds real text is treated as empty, with no cursor move. A stand-in without a cursor gets the dictation through `ACTION_SET_TEXT` (it has nothing to lose), verified against the reported placeholder, which leaves the clipboard alone; one with a cursor is still pasted into. Verified in the chat box: the field read back as exactly the dictated sentence, a second dictation was appended after one space, and the box was then emptied (no draft left, nothing sent). (2026-09-26: narrowed to fields proven empty; see the sixth round.) |
| "Sentences are often split up too much" (reported on Windows; the same code runs here) | `min_turn_silence` 1000 ms and `max_turn_silence` 3600 ms, and the cleanup prompt rule and Light example about full stops placed at pauses, as in the Windows app. AssemblyAI's 100 ms default ended a turn, and so a sentence, at every pause to think. The phone's `STREAM_TEST` of the test recording went from several sentence-turns to one. |
| "I often pause for several seconds mid-sentence; can the cleanup rejoin broken sentences?" (2026-09-26, both apps) | The cleanup LLM receives the transcript with `[pause]` between turns and the transcriber's guess at each pause removed (the full stop ending a turn dropped, the next word lower-cased except "I", acronyms and mixed-case names; `?` and `!` stay), with a prompt rule to decide afresh and restore names; any marker left in the output is stripped. Not at None. Light, Medium and High say they rejoin sentences broken at pauses; Medium now says "break up only genuinely run-on sentences". History's raw transcript and the no-LLM fallback stay unmarked. Measured on the Windows app against gpt-oss-120b: 24/24 correct on three broken and three genuine sentence pairs at Light, Medium and High/Formal, against about one in three joins at Light for the reported example with a flat transcript. The full stop left in place anchored the model, so it is removed at each pause. |

Verified on the device: the bubble appears above Gboard in a Compose field and in Chrome's address bar, and hides
when the keyboard closes; a full dictation (AssemblyAI connect 943 ms, handshake 982 ms, Groq gpt-oss-120b cleanup
692 ms) typed "So I think we should ship the new version on Tuesday. What do you think?" from "Um, so I think we
should... We should ship the new version on Monday, no? Tuesday. What do you think?"; tap → hands-free pill → ✕
discards; hold → release finalises ("Nothing heard" in a silent room); drag snaps to either edge and remembers its
height; drop on the target snoozes and "Show now" restores; History lists, shows details and plays audio. Chrome's
address bar took the text through the direct path (no clipboard).

## Sixth round (2026-09-26): review fixes

A review of both apps (bug and improvement reports, each claim checked against the code) was implemented in both
repositories. The user decided five questions it raised:

| Question | Decision |
|---|---|
| When may a dictation be discarded? | Only while it records (the bubble held, or the hands-free pill before ✓). After the release or ✓ it always finishes into the field or onto the clipboard and is kept in History. |
| What if the app is unsure whether the text can be inserted? | It is copied to the clipboard as well. |
| Should the Windows repository be public? | Yes, on purpose: "Check for updates" then works without a token. |
| Bluetooth headsets? | One is sometimes connected, but dictation should use the phone's own microphone. |
| What does "Delete all history" delete? | Everything: every record, every recording (orphans included) and the logs. This app already did. |

| Change | Decision | Why |
|---|---|---|
| Recording boundary | The microphone starts before the field lookup; releasing before `Begin` stops it at once and only what was recorded is sent; a short last read is zero-padded to a whole frame. | Speech during the lookup was lost, the microphone kept recording and uploading until the connection finished, and a stop mid-frame dropped the last words or sent a chunk shorter than AssemblyAI accepts. |
| Discard only while recording | ✕ and slide-away work while recording only; the state machine ignores Cancel in Finalising and Post-processing. | The user's decision. |
| Service stopped mid-dictation | Switching the accessibility service off (or Android stopping it) while recording keeps the dictation as a Failed "Interrupted" record with its audio and the words heard, with no network call or insertion; after the release the dictation finishes. Supersedes the second Codex review's discard. | A discard is only ever the user's choice (decision 1), and the Windows app keeps a dictation interrupted by quitting the same way. |
| Crash recovery | Recordings are finished (WAV sizes written) only after their History record is saved; closing without `complete()` leaves sizes of zero. At the next start `InterruptedRecordings` repairs such files and lists them as Failed for Retry; shorter than 300 ms or past retention they are deleted. | A crash, a kill or a flat battery mid-dictation left audio no record referred to, which the orphan sweep deleted. |
| Retry | Streams at real time, reading the WAV frame by frame; a retry without a Termination summary keeps the earlier words and stays retryable; cost uses the billed session time. | AssemblyAI processes streamed audio at about 1.25x real time at most, so the 4x replay came back cut off. |
| Cleanup | A reply whose `finish_reason` is not `stop` is never used; only 401 ends the model chain (403 moves on); the record keeps every attempt's reason; a recurring settings problem is toasted once per run. Test keys tries every model of the chain. | As on Windows. |
| Insertion | A field's whole text is replaced only when it is proven empty: Android's own marker (a TextView offering the rendering-info extra but not the character-location extra) or WhatsApp's chat-box id. A field that only looks like a stand-in (short text, no cursor, no cursor actions) keeps its text; the dictation goes at its end and is also copied, and a toast says so (the bubble cannot show text, so the "also copied" badge only complements the others: "cleanup skipped · also copied" still shows the amber ring). Text equal to the hint counts as the hint only without signs of real text. | The earlier rule (see the on-device findings) took missing accessibility metadata as proof of emptiness, so a Flutter or custom field holding a real draft lost it. |
| Focus lost before insertion | The field the dictation started in is still used when no field has input focus (an app may drop focus when the keyboard closes), but it is never pasted into once it has lost focus: the text is copied instead. Setting its text directly still works. | Chromium reports a paste into a blurred web field as done although nothing arrives. The fallback stays because native fields take the direct path without focus. |
| One delivery step | Live dictation and History's Insert share the orchestrator's delivery: the destination app's rule, the password and no-field checks, a clipboard copy after a failed insertion, the outcome toasts, and a lock so clipboard writers take turns. | History's Insert called the bridge directly and gave no outcome. |
| Microphone | "Default" is the phone's own microphone whenever a headset or USB microphone is also connected (with the phone's alone, Android's choice is untouched). A chosen Bluetooth headset is brought up through its **output** with the same address (a communication device must be a sink); the phone's microphone records until the headset's call link is up and the recording switches over between frames; if the link is not up within 2.5 s the phone's microphone keeps recording and one toast per run says so. Android 11 uses the SCO switch, which needs `MODIFY_AUDIO_SETTINGS` (declared with `maxSdkVersion` 30). | The user's decision 4. Passing the microphone itself to `setCommunicationDevice` was refused, so a chosen Bluetooth microphone faulted or recorded silence. |
| Settings file | Written to a temp file, synced, and renamed over the old one in one step (no delete fallback). A failed write keeps the settings as they were and toasts instead of crashing the button or drag that asked. The dictation-length and language fields save on Done, focus loss and leaving the screen, not at every keystroke. | A crash between the delete and the rename lost every setting, the encrypted keys included. |
| Website rules | The focused window's address bar is read first; only address-shaped text counts (a search being typed is no website); the last address is remembered for three minutes after the bar scrolls away, refreshed at every focus and window change. An address that cannot be read at insertion is no sign of a move. | The address was remembered for ever, so a site's rule followed the user to other tabs, and a hidden bar at insertion dropped the site's insertion method. |
| Smaller | The correction diff sets aside the shared start and end and refuses over 2,000 x 2,000 words (a quarter of Windows' budget: a phone's heap is smaller); "microphone unavailable" is told apart from "blocked"; one text-free timing line per dictation; an empty History does not sweep its recordings as orphans; CI cancels superseded pull-request runs and no longer publishes the APK; keystores are git-ignored and the README says never to uninstall. | Each was a verified finding of the review. |

Verified on the Galaxy S24 (Android 16, 2026-09-26, build 0.2.0): WhatsApp's empty chat box offers only the
rendering-info extra, so it is proven empty without its id, and a typed draft adds the character-location extra,
a cursor and the cursor actions; a dictation into the empty box read back as exactly the dictated sentence, and
into the box holding "Draft" as "Draft " followed by the sentence; the search bar kept its zero-width space and
took the sentence after it by paste. With a JBL Live 770NC connected, "Default" recorded from the phone's
microphone with no Bluetooth route requested; with the JBL chosen, the phone's microphone recorded first and the
call link was up 0.8 s later, Android listing the app's request as the headset's output (`bt_sco_hs`, role
output); the request was withdrawn when the test stopped.

Found testing Gmail and Outlook on the phone the same day: the bubble never appeared in Gmail's compose screen.
Gmail marks it data-sensitive, and Android 14+ hides such views from accessibility services that are not declared
accessibility tools: the service saw Gmail's compose window but no tree (`DUMP_WINDOWS`: root null, input focus
null), while `uiautomator` saw every field. The service now declares `isAccessibilityTool` (dictation is an input
tool; a personal build has no store policy to meet). Then the Subject took the dictation as its text (proven
empty), the body (a web editor) took it by paste at the cursor with the signature card and its formatting intact,
and a follow-on dictation joined after one space. Outlook needed no change: its Subject behaves like Gmail's, and
its body (Microsoft's Rooster web editor) took dictations at the cursor above the signature, joined correctly.
With a JBL Live 770NC chosen, a spoken dictation into WhatsApp recorded from the headset after the call link came
up; with Default, spoken dictations into Gmail and Outlook recorded from the phone. A process killed mid-dictation
was restarted by Android within 2 s with the service reconnected; the recording was recovered, toasted and listed,
and Retry transcribed it at real time (9 s for 5.6 s of audio). History's Insert delivered into WhatsApp's search
bar, and copied with a truthful toast when a fingerprint prompt (a locked chat) was in front.

Pre-push review (same day), fixed before the first push:

| Finding | Fix |
|---|---|
| The hourly orphan sweep deleted unfinished recordings: those a failed save left for listing at the next start, and those recovery never listed because a database error ended it (recovery was marked done before it ran). With a lost database and one interrupted recording, recovery made History non-empty before the empty check, so every other recording was swept. | The sweep skips unfinished recordings, recovery is marked done only after it succeeds, and History's emptiness is judged before recovery. (Windows had the same three; fixed there too.) |
| "Also copied" was never shown (the bubble draws no badge text) and hid the amber ring when combined. | Badges are matched by content; an uncertain insertion toasts. |
| Leaving History's detail screen cancelled a running Retry (now real time, so as long as the recording), aborting a billed session. | Retry runs in the app's scope. |
| "Scratch that" right after "new line" deleted the sentence before it. | Only the line break is retracted (both apps). |
| A test of the discard/release race passed without the fix. | It now checks the "Discarded" outcome. |

Codex review of the pull request (2026-09-27):

| Finding | Response |
|---|---|
| Recovery repaired a recording's header before listing it, so a database error left a finished, unlisted file that the sweep later deleted. | The sizes are written only once History lists it (both apps). |
| A release, discard or teardown during a slow field lookup did not stop the microphone until the lookup returned. | The lookup runs beside the session and those stop the microphone at once (both apps). |
| A Retry that outlives the History screen could run beside a live dictation, both streaming and both ending on the clipboard. | One at a time: a press during a Retry shakes the bubble; a Retry during a dictation is refused (both apps). |
| "`setCommunicationDevice` needs MODIFY_AUDIO_SETTINGS on Android 12+, so every Bluetooth route silently fails." | Not changed. AOSP's `setCommunicationDevice` (Android 14 and current main) checks no permission, and on the Galaxy S24 (Android 16), where the app does not hold the permission, the JBL's route came up and Android listed the request. The permission stays declared for Android 11's SCO switch only. |

Its second round (on 4a4c292):

| Finding | Response |
|---|---|
| A recording that could not be read at recovery was taken for a finished one, so recovery counted as done and the recording stayed out of History until the next start. | Recovery is done only once every recording could be checked; one that could not be read is tried again at the next pass, and the orphan sweep keeps it meanwhile (both apps). |
| A field lookup that never returns held the session before the duration cap was running, so the microphone could record without limit. | The lookup is given up after 5 s, at the start and again at insertion; the dictation goes on as for an unknown field (both apps). |
| A Retry went on streaming a dictation deleted meanwhile (or by Delete all history), then copied it and reported success. | Deleting that dictation, or Delete all history, stops its Retry first; the Retry takes its cancellation handle in the same step as its reservation (both apps). |
| A failed save of an API key still cleared the pasted key from the form. | The form is cleared only once the key is saved. Windows already kept its fields until a save succeeded. |

Found while fixing those: a Retry whose connection timed out ended without a word, because the time limit's cancellation
was taken for its caller's. It now fails like any other error, with "Retry failed" and the reason on the record (both
apps).

From Codex's review of the Windows pull request the same day: the orphan sweep judged an empty History afresh at each
pass, so the first new record let the next pass delete every recording of a lost database. A pass that finds History
empty with recordings in the folder now saves the time (`sweep-cutoff.txt`), and the sweep never deletes a recording
written before it; Delete all removes both (both apps). Its paste-proof and spacing-memory findings concern Windows'
read-back of the caret: Android's paste never restores the clipboard, and counts as inserted only when the field
accepted ACTION_PASTE.

Deliberate differences from Windows: Windows proves a paste by reading back the text before the caret; Android
sets text directly and reads the whole field back, so its uncertain case is a field that only looks like a stand-in,
and its paste path always leaves the text on the clipboard (Android cannot restore it). The correction diff budget
is smaller. Bluetooth routing exists only here.

Porting rule: the two apps share their core design line by line. A change to shared behaviour (session lifecycle,
cleanup, retry, history, settings, formatting) is made in both repositories in the same round, each with its own
regression test, and a deliberate difference is recorded in both decisions files.

## Testing notes

- JVM tests: the Windows Core test cases ported (normaliser, lists, validator, prompt, assembler, protocol
  parsing, session options, rules, keyterms, differ, formatter, cost, settings store, WAV I/O and repair, state
  machine), the LLM client against MockWebServer (fallback chain, 401 stop, cut-off replies, validation,
  per-attempt and total timeouts), the orchestrator with in-memory fakes (lifecycle, discards, teardown, delivery,
  History Insert, Retry, cost), interrupted-recording recovery, the SQLite/FTS4 history, the placeholder check and
  the insertion routes under Robolectric, microphone selection, the website memory, and the Android
  regex-compatibility scan. The README does not quote a count, which goes stale.
- The accessibility, overlay and microphone code needs a device; see `manual-test-checklist.md`.
