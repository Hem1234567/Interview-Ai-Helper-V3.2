# Summary of changes (v2.1)

## Voice: continuous listening, exact words
* Listening no longer stops when you pause. Every phrase is transcribed in the background and **appended** to the text box; it keeps listening until you tap the mic (or Generate Answer).
* Nothing is generated until you tap **Generate Answer**. Tapping it while still listening stops the mic, waits for the last words, then answers with everything you said.
* New **Clear** button next to Generate Answer (it replaces Stop while an answer is running). Clearing while listening keeps the mic on.
* Accuracy: language defaults to English (auto-detect often mis-hears accents), each phrase gets the previous words as context, noise "ghost" phrases and word loops are filtered, 450 ms pre-roll, beam-search decoding per phrase.
* New speech engine **Groq Whisper large-v3-turbo** (needs a Groq key): best accuracy. Auto order: Groq, on-device Whisper, phone recognizer. Failed engines are skipped and the next one is used.
* The phone recognizer fallback now restarts itself, so pauses do not stop it either.

## Interview page: engine picker
* Top-left pill with up/down arrows opens the engine list: **Auto, Local, OpenRouter, Google, Groq**, with live status (ready, no key, limit reached + retry time).
* **Auto**: Groq, Google, OpenRouter (fastest first). On failure or a limit (HTTP 429/402, bad key, 5xx, no first word in 2.5 to 4.5 s) it switches to the next engine at once and skips the failed one for a while; if all online engines fail, the on-device model answers.
* A fixed choice uses exactly that engine and tells you if it fails.

## AI tab (new)
* Engine choice, API key and model for Groq, Google (Gemini) and OpenRouter, each with a **Test** button (shows OK and the time, or the error). Local models link.
* Speed: Groq `llama-3.3-70b-versatile`, Gemini `gemini-3.1-flash-lite` with thinking minimal/off, OpenRouter latency routing, connection pre-warming when the mic starts, hard first-word timeouts, in-flight request cancel, short default answers.

## Personal tab (new)
* Name, one line about you, **10th / 12th / college marksheet upload** (photo or PDF, several pages allowed).
* On-device text recognition (ML Kit) rebuilds the table rows, then the app finds subject marks, total, printed percentage, CGPA/GPA (or computes a credit-weighted GPA from a grade table), board and year, and **calculates** the percentage. Optional AI double-check (switch) merges a second reading; everything is editable and the scanned text can be shown.
* Résumé and vocabulary moved here. The profile (name, marks) is given to the answer prompt.

## Navigation
* Tabs: Assistant, Personal, AI, History. **Practice and Jobs** moved to Settings.

## Build and CI
* ML Kit text-recognition dependency added; version 2.1. ktlint and the colour script pass; 48 unit tests (voice flow, speech text, engine order and cooldowns, marksheet parser).
