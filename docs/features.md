# Features

DictationApp for Android is the phone counterpart of the Windows tray app. Where Windows has a hotkey and the
Flow bar, Android has a floating bubble; everything behind it (streaming, cleanup, rules, dictionary, history) is
the same design and, in `core/`, a line-by-line port of the same code.

## The bubble

| Feature | Behaviour |
|---|---|
| When it appears | An editable text field has focus and the on-screen keyboard is open (Wispr Flow's rule). Settings › Bubble › "Only while the keyboard is open" can be switched off for hardware keyboards. It never appears in password, PIN, number, phone or date fields, or in apps listed under "Hide the bubble in these apps". While a dictation runs it stays visible even if the keyboard closes. |
| Where it sits | On the left or right edge, a set distance above the top of the keyboard. Dragging it moves it; on release it snaps to the nearest edge and the height above the keyboard is remembered. |
| Size and opacity | Size 50–160% of 44 dp; idle opacity 20–100% (default 80%, Wispr Flow's default). It is always fully opaque while dictating. A live preview in Settings shows both. |
| Shrink when idle | Optional: after five seconds without use it shrinks toward the edge; any touch restores it. |
| Tap | Starts a hands-free dictation. The bubble becomes a small pill: **✕** discards, the red middle shows the live level, **✓** (or tapping the middle) inserts. |
| Hold | Push-to-talk: dictation starts 150 ms after the finger goes down (so a drag is never mistaken for a press), release to insert. Sliding the finger more than about two bubble widths away turns it grey with a cross; releasing there discards. A hold shorter than 300 ms counts as a tap and continues hands-free, the same 300 ms threshold the Windows hotkey uses. |
| Drag to hide | While dragging, a **Hide 10 min** target appears at the bottom; dropping the bubble on it snoozes it for ten minutes. The Home screen shows a "Bubble is hidden · Show now" card. |
| States | Dark with a white microphone when idle; grey when there is no network; red with five level bars while listening; breathing blue while finishing, cleaning up and inserting; an amber ring when cleanup was skipped, a red ring on failure, a shake when nothing was heard or when it is tapped while busy. |
| Haptics | A tick when dictation starts, a click when it stops; can be switched off. |

## Dictating

As on Windows: audio is captured from the moment the press is confirmed and buffered until AssemblyAI's `Begin`
arrives (~1 s), so nothing is lost to connection latency. A dictation stops automatically after 20 minutes
(configurable 1–180). Streaming uses Universal-3.5 Pro by default (switchable to Universal Streaming), with the
personal dictionary as `keyterms_prompt` and the same shutdown handshake (silence tail → ForceEndpoint → end of
turn → Terminate, capped at 2.5 s).

## Where the text goes

| Situation | Result |
|---|---|
| A normal text field has focus (default "Type into the field") | The text is spliced in at the cursor (replacing any selection) through the accessibility API, then read back to confirm. The clipboard is untouched. |
| The field ignores that, is web content, or the app rule says Paste | The text is put on the clipboard and pasted. Android does not let a background app read the clipboard, so the previous contents cannot be restored. |
| An empty field that reports its placeholder as its text (WhatsApp's "Message" box and "Ask Meta AI or Search" bar) | Recognised as empty, so the placeholder is not copied into the text. A field with a cursor (the search bar) is pasted into at that cursor; one without (the chat box, which refuses a paste) gets the dictation as its text, leaving the clipboard alone. |
| Sentences and pauses | AssemblyAI waits for 1 s of silence before ending a sentence at a full stop (its default is 100 ms). Longer pauses to think still end a turn, so the cleanup (Light and above) is told where each pause was, with the transcriber's full stop there removed, and rejoins the sentence when the words after the pause continue it. Real sentence ends and names are restored; the inserted text and History never contain the pause markers. |
| The field accepts neither | The text stays on the clipboard and a toast says so. |
| No text field has focus, or the focused field is a password | Clipboard only, with a toast. |
| Focus moved to another field during the dictation | The text goes into the new field; the tone chosen at the start is kept. |
| Spacing | The character before the cursor is read, so a space is added when needed and a sentence start is capitalised; a space is also added after the text when it would run into the next word. Where the field's text cannot be read, the last insertion into that field is remembered instead (the Windows method). |

## Cleanup, tone, app rules, dictionary

Identical to Windows (see the Windows `docs/features.md`): Groq's OpenAI-compatible API with the same system
prompt, levels None/Light/Medium/High, tones Neutral/Formal/Casual, the regex pass for spoken commands and lists,
the `openai/gpt-oss-120b` → `qwen/qwen3.8-27b` → `openai/gpt-oss-20b` free-tier chain (4 s per model, 8 s total),
and output validation with raw-text fallback.

App rules match an Android **package name** (wildcards `*` `?`, picked from a list of installed apps) or a
**website host** read from the address bar of Chrome, Edge, Brave, Vivaldi, Opera, Firefox, Samsung Internet or
DuckDuckGo. A website rule beats an app rule, which beats the defaults.

The list starts **empty**: every app follows the default tone and cleanup (Settings › Style) and the default
insertion method (Settings › General). **Add app** opens the list of installed apps and starts the new rule from
your current defaults, so you only change what should differ; **Add website** does the same for a browser host.
Useful examples:

| Target | Tone | Level | Insertion |
|---|---|---|---|
| Gmail, Outlook, Word, Google Docs (apps) | Formal | Medium | Paste (keeps signatures and formatting) |
| mail.google.com, docs.google.com | Formal | Medium | |
| WhatsApp, Messages, Teams, Slack, Signal | Casual | Light | |
| Termux | Neutral | None | |

Version 0.1.0 seeded rules like these; settings schema 2 removes them on upgrade while they are still seeds (tone
and level may differ). A seeded rule whose insertion method, hint or on/off switch you changed is kept, as is every
rule for another app.

The personal dictionary (up to 100 key terms, starred first) and **Correct last dictation** work as on Windows.

## History

Every dictation is stored in SQLite with the same fields as Windows (package name and app label in place of the
process name). The History tab has prefix full-text search, and each dictation has Play/Stop, Copy, **Insert**
(moves DictationApp to the background and types into the field that has focus, the Android "Re-insert"),
Retry (re-streams the stored WAV at 4× for failed or pending dictations and copies the result), Undo AI edit
and Delete. Retention (24 hours / 14 days / forever, separately for text and audio), "Store audio" and "Delete
all history" are in Settings › History & privacy; retention runs at startup and hourly and sweeps orphaned WAVs.

## Settings

Bubble · Style (default tone and level) · Dictionary · App rules · API & models (Keystore-encrypted AssemblyAI and
Groq keys, Test keys, speech model, cleanup endpoint/model/fallbacks) · Audio (input device, including USB and
Bluetooth headsets, with a live level test) · General (insertion method, dictation limit, language codes) ·
History & privacy · About (version, share today's log).

## Not carried over from Windows

| Windows | Android | Why |
|---|---|---|
| Hotkey chord, double-tap, arrow keys, "accept injected keys" | Tap and hold on the bubble | No keyboard shortcut exists across Android apps. |
| Flow bar Full / Minimal / Hidden | The bubble only | Requested: no full floating window on Android. |
| Tone/level chips and "Remember tone and cleanup changes" | Default style and app rules in Settings | Without the full bar there is nowhere to change the style mid-dictation, so the setting would have no effect. |
| Clipboard snapshot and restore | Type directly by default | Android 10+ forbids background clipboard reads. |
| Elevation detection | Password detection | Android has no elevated windows; secret fields are the equivalent "do not type here" case. |
| Autostart, Velopack updater, GitHub token | — | Android restarts accessibility services itself; the APK is installed by hand. |
