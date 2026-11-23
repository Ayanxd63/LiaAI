# V19 — Licences & provenance (Pocket TTS is back)

V19 restores ONE on-device engine from V17 — Pocket TTS only — for
fast voice (the user: "Fast voice chaye. Replay to Aya he NHI").
Everything in this file is what is ACTUALLY in the app now. Nothing
is copied from Maya (no code, prompts, models, `.so` binaries, ogg
samples, art, palette, or endpoints; no getmaya.online use).

## Restored in V19 (Pocket TTS only)

| What | Licence | Source / version |
|---|---|---|
| sherpa-onnx v2.0.3 (runtime only) | Apache-2.0 | GitHub releases `k2-fsa/sherpa-onnx` v2.0.3, SHA-256 checked in CI (see `.github/workflows/android.yml`). CI extracts arm64-v8a + x86_64 `.so` files; the V18 arm64-only filter ships just arm64 (~31.5 MB in the APK). |
| Pocket TTS int8 model + Lia/Loona voice references | CC-BY-4.0 (Kyutai Pocket TTS, via sherpa-onnx export) | Hugging Face `csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26` — ~191 MB, Wi-Fi-only download on first enable from Settings → On-device voice, SHA-256 verified, never bundled in the APK. Attribution shown in that screen. |

## Still removed (was in V17, stays out in V19)

| What | Why it stays out |
|---|---|
| llama.cpp offline LLM (Qwen2.5-0.5B, ~212 MB native libs per ABI) | Offline AI cut for the diet — chat is online-only now (Pollinations / Gemini-with-key). |
| Moonshine STT, ERes2Net speaker voice-match, Zipformer laugh detection, Silero VAD gate (all sherpa-onnx engines except Pocket TTS) | On-device voice slimmed to Pocket TTS only. Their model descriptors were deleted from VoiceModelManager; nothing in the app can download or reference them. |
| TensorFlow Lite 2.17.0 (Apache-2.0) — MiniLM-L6-v2 memory embeddings | Embeddings cut with the diet; memory search is keyword/recency only now. |

## Runtime (still in the app)

| What | Licence | Source / version |
|---|---|---|
| Vosk wake-word spotter (vosk-android 0.3.45) | Apache-2.0 | Maven Central `com.alphacephei:vosk-android:0.3.45`. The ~40 MB wake-word model downloads on first enable (Wi-Fi only), never bundled in the APK. |
| OkHttp 4.12.0 | Apache-2.0 | Maven Central. Used by Pollinations chat/image + the online Kokoro voice. |
| WorkManager 2.9.0 | Apache-2.0 | AndroidX. Background watchdog for always-listening. |
| security-crypto 1.1.0 | Apache-2.0 | AndroidX. Keystore-backed encrypted credential storage. |

## Online voice (internet, no key)

| Engine | Service | Terms | Endpoint |
|---|---|---|---|
| Online voice (Kokoro-82M) | mesmer.tools TTS API | Free, no key, no signup; 20 generations/hour per IP; text ≤ 2,000 chars/request | `https://mesmer.tools/api/v1/tts` (docs: `https://mesmer.tools/api-tools/tts`) — voices `af_bella` (default, warm American female) and `hf_alpha` (Hindi female, auto-used for Devanagari text); quality `low` (Kokoro, default) / `high` (Azure GPT-audio narrator, falls back to Kokoro on error — the response reports `fellBack`) |

Verified live 2026-11-22: `low` + `af_bella` and `low` + `hf_alpha` (Hindi text) both returned real CDN MP3s (provider kokoro, `fellBack` false); the af_bella MP3 downloads and plays (MPEG layer III, 24 kHz mono, 2.25 s). `high` + `hq_female` worked for a cached text (provider openai, `cached` true) but a fresh high render failed the same day with an Azure OpenAI 530 and fell back to Kokoro — high is an option, never the default. The 20/hour limit is server-enforced per IP; the app keeps a local counter to fail fast and shows the count in Settings. mesmer.tools is a small third-party service — it is never the only voice; the chain always falls back to system TTS.

## Deliberately NOT used

- **Kyutai `kyutai/pocket-tts` (gated safetensors)** — gated ("auto": must
  accept prohibited-use terms with an HF account); cannot be fetched
  anonymously in-app.
- **Indus Hindi Pocket TTS** (`Indus-Labs/indus-pocket-tts`, CC-BY-4.0)
  — no published ONNX export sherpa-onnx can load. (Still moot in V19:
  Pocket TTS is back, but the Hindi voice is a future export job.)
- **Maya's models, sounds, art, prompts, endpoints** — never copied.
- **YAMNet** — never used; the V17 laugh detector used sherpa
  AudioTagging instead (removed in V18, stays out in V19).
- **MediaPipe LLM Inference** — maintenance-only since mid-2026 (V15
  decision, still stands).

## Notes

- Pocket TTS has NO Hindi: it speaks English (+ fr/de/pt/it/es) and
  reads Roman Urdu with English pronunciation. (Back in V19; the Hindi
  gap is unchanged.)
- Moonshine tiny-en was English-only; Urdu/Hindi speech always used
  Android's SpeechRecognizer — and still does.
- Speaker voice-match was a similarity score, NOT biometric
  verification. (Removed in V18, stays out in V19.)
