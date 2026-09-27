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
- [ ] After ✓ or a release, nothing discards any more: the bubble breathes blue and the text arrives.
- [ ] Release right after the press, before the bubble turns red: the words said are inserted, and words spoken
      after the release are not in the text.
- [ ] Switch the accessibility service off while recording: History has a Failed "Interrupted" record with Retry.
- [ ] Kill the app while recording (`adb shell run-as com.thomaswcode.dictationapp kill -9 <pid>`, or restart the
      phone): Android restarts it, a "Dictation recovered" toast appears, and History has a Failed record whose Retry
      transcribes it. (App info › Force stop works too, but Android then switches the bubble service off until it is
      switched on again.)
- [ ] Hold: red with moving bars; release inserts; slide away turns grey with ✕ and releasing discards.
- [ ] Tapping the bubble while it is breathing blue shakes it and does nothing else.
- [ ] Speak "new line", "period", "scratch that", "number one … number two …": applied correctly.
- [ ] With no Groq key: raw text inserted, amber ring (cleanup skipped).
- [ ] Nothing said: bubble shakes, no record.
- [ ] Dictating for over the limit (set 1 minute): stops with a toast and inserts.
- [ ] Switch to another app mid-dictation (hands-free), focus a field there, ✓: text goes into the new field.
- [ ] Wi-Fi off mid-dictation: "Network error" toast, Failed record with audio; Retry in History copies the text.
- [ ] Start a Retry, leave History, and tap the bubble in a text field: it shakes (busy) until the Retry's toast.
- [ ] Revoke the microphone permission: tapping the bubble opens the app and asks for it.

## Apps

| Target | Expect |
|---|---|
| Google Messages / Samsung Messages | Default style, typed directly |
| WhatsApp | Default style, typed directly; add a WhatsApp rule (e.g. Casual) and check it applies. Into the empty chat box and the empty search bar, only the dictation appears (no "Message" or "Ask Meta AI or Search"); a second dictation into the chat box follows after one space. Re-test after 2026-09-26: `DUMP_FOCUS` on the empty chat box should show `platformEmpty=true` or `provenEmpty=true`; if "Message" appears in front, send that line |
| A Flutter app's text field holding a short draft | The draft is kept, the dictation follows it, and an "Inserted and copied" toast appears |
| Gmail and Outlook, new email: Subject, then the body above the signature | The bubble appears in both fields; the Subject gets the dictation; the body gets it at the cursor with the signature (and its formatting) intact; a second dictation joins after one space |
| A web page's input, keyboard closed before ✓ | "Copied to clipboard: The field lost focus", never reported as inserted |
| Gmail compose | Default style; with a Gmail rule set to Paste, signature formatting is kept |
| Chrome: Gmail web, Google Docs web, a plain textarea | Pasted (web content); a website rule, if added, applies |
| Chrome address bar | Bubble shows; text goes into the omnibox; a website rule does not apply while typing there |
| Chrome with a mail.google.com rule, page scrolled so the bar is hidden | The rule still applies (the address is remembered for three minutes); in another tab after that, it does not |
| Samsung Notes / Google Keep | Default style |
| Termux | Default style (add a rule with cleanup None for raw text) |
| DictationApp's own "Try it" field | Works |

## Audio

- [ ] Bluetooth headset connected, microphone "Default": the Audio test says it is recording from the phone
      microphone, and a dictation uses the phone's microphone.
- [ ] Bluetooth headset chosen: the Audio test switches to "Bluetooth headset (…)" within a second or two; with the
      headset unable to connect, a toast says the phone's microphone is recording.
- [ ] Wired or USB headset chosen: the test records from it.

## History and settings

- [ ] Search finds by prefix ("isoni" finds "isoniazid") and by app name.
- [ ] Play, Copy, Insert (returns to the previous app and types there, with the same toasts as a dictation; with no
      field focused, "Copied to clipboard"), Undo AI edit, Delete.
- [ ] Correct last dictation proposes changed words; adding them stars them in the dictionary. After a long
      dictation, one fix is still found; changes spread over the whole text give the "too far apart" message.
- [ ] Test keys lists every model of the chain.
- [ ] Type "en, de" into Language codes: saved on Done or on leaving the screen, not letter by letter.
- [ ] Retention 24 hours on audio removes older WAVs (check the record loses Play).
- [ ] Delete all history empties the list.
- [ ] Reboot the phone: the bubble works again without opening the app.
