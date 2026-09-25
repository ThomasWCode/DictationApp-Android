# Manual test checklist

Run on a real phone after `adb install -r app-debug.apk`. For each app row: the bubble appears with the
keyboard, tap and hold both insert, spacing is right after a second dictation, and the history record shows the
right app, tone and status.

## Setup

- [ ] Fresh install: Home lists AssemblyAI key, Microphone, Bubble, Groq key, Keep running.
- [ ] Keys saved in Settings › API & models; "Test keys" reports both OK.
- [ ] Accessibility service switched on (sideloaded APK: App info › ⋮ › Allow restricted settings first).
- [ ] Home says "Ready" and the "Try it" field shows the bubble when tapped.

## Bubble

- [ ] Appears when the keyboard opens in a text field; disappears when the keyboard closes.
- [ ] Not shown in a password field, a PIN field, a phone-number field or a calculator.
- [ ] Size and opacity sliders change the preview and the real bubble; opacity returns to 100% while dictating.
- [ ] Drag to the other edge: snaps, and stays there next time. Height above the keyboard is remembered.
- [ ] Drag onto "Hide 10 min": bubble disappears; Home shows "Bubble is hidden"; "Show now" brings it back.
- [ ] Shrink when idle: shrinks after 5 s, restores on touch.
- [ ] Airplane mode: bubble turns grey.

## Dictating

- [ ] Tap: pill with ✕ / level / ✓; ✓ inserts; ✕ discards (no history record).
- [ ] Hold: red with moving bars; release inserts; slide away turns grey with ✕ and releasing discards.
- [ ] Tapping the bubble while it is breathing blue shakes it and does nothing else.
- [ ] Speak "new line", "period", "scratch that", "number one … number two …": applied correctly.
- [ ] With no Groq key: raw text inserted, amber ring (cleanup skipped).
- [ ] Nothing said: bubble shakes, no record.
- [ ] Dictating for over the limit (set 1 minute): stops with a toast and inserts.
- [ ] Switch to another app mid-dictation (hands-free), focus a field there, ✓: text goes into the new field.
- [ ] Wi-Fi off mid-dictation: "Network error" toast, Failed record with audio; Retry in History copies the text.
- [ ] Revoke the microphone permission: tapping the bubble opens the app and asks for it.

## Apps

| Target | Expect |
|---|---|
| Google Messages / Samsung Messages | Default style, typed directly |
| WhatsApp | Default style, typed directly; add a WhatsApp rule (e.g. Casual) and check it applies. Into the empty chat box and the empty search bar, only the dictation appears (no "Message" or "Ask Meta AI or Search") |
| Gmail compose | Default style; with a Gmail rule set to Paste, signature formatting is kept |
| Chrome: Gmail web, Google Docs web, a plain textarea | Pasted (web content); a website rule, if added, applies |
| Chrome address bar | Bubble shows; text goes into the omnibox |
| Samsung Notes / Google Keep | Default style |
| Termux | Default style (add a rule with cleanup None for raw text) |
| DictationApp's own "Try it" field | Works |

## History and settings

- [ ] Search finds by prefix ("isoni" finds "isoniazid") and by app name.
- [ ] Play, Copy, Insert (returns to the previous app and types there), Undo AI edit, Delete.
- [ ] Correct last dictation proposes changed words; adding them stars them in the dictionary.
- [ ] Retention 24 hours on audio removes older WAVs (check the record loses Play).
- [ ] Delete all history empties the list.
- [ ] Reboot the phone: the bubble works again without opening the app.
