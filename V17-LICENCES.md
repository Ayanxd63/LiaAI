# V17 — On-device voice: licences & provenance

All V17 engines use PUBLIC upstream code and models only. Nothing is
copied from Maya (no code, prompts, models, `.so` binaries, ogg
samples, art, palette, or endpoints; no getmaya.online use). Every
URL + size + SHA-256 below was verified live on 2026-11-22 against
the Hugging Face API (LFS oids are the file SHA-256s; small non-LFS
files were downloaded and hashed on the spot). Models are downloaded
on first enable (Wi-Fi only), never bundled in the APK. The in-app
attribution block (Settings → On-device voice) shows the same list.

## Runtime

| What | Licence | Source / version |
|---|---|---|
| sherpa-onnx v2.0.3 (single on-device voice runtime: TTS, STT, VAD, speaker embeddings, audio tagging) | Apache-2.0 | GitHub release v2.0.3, 2026-11-22. Tarball `sherpa-onnx-v2.0.3-android.tar.bz2` 47,988,767 bytes, sha256 `2a30b222abae6d5cf544df4a1c27944279449c25dbb9c755097189cb45db1df0`. JVM jar `sherpa-onnx-jvm-2.0.3.jar` 198,059 bytes, sha256 `ad69b6a54462ba3671782617b11d35975c8d6b7c84ae11765e3a06dd0c56c0e5`. Bundled ONNX Runtime 1.28.2. NOT on Maven Central — CI downloads it (see `.github/workflows/android.yml`). |
| TensorFlow Lite 2.17.0 (memory embeddings runtime) | Apache-2.0 | Maven Central `org.tensorflow:tensorflow-lite:2.17.0` (latest, verified 2026-11-22). |

## Models (all downloaded on demand, Wi-Fi only)

| Engine | Model | Licence | Repo |
|---|---|---|---|
| 1. On-device TTS (Lia voice) | sherpa-onnx Pocket TTS int8 (Kyutai Pocket TTS export) + bria/loona reference wav | CC-BY-4.0 | `csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26` (~191 MB, 9 files; full SHAs in `VoiceModelManager.kt`) |
| 2. On-device STT | Moonshine tiny-en int8 | MIT (Useful Sensors, 2024) | `csukuangfj/sherpa-onnx-moonshine-tiny-en-int8` (~118 MB, 5 files) |
| 3. Speaker voice-match | ERes2Net base (3D-Speaker) | Apache-2.0 | `onemira/eres2net-base-3dspeaker-onnx` (~38 MB, 1 file) |
| 4. Laugh detection | Zipformer audio tagging int8 | Apache-2.0 | `k2-fsa/sherpa-onnx-zipformer-audio-tagging-2024-04-09` (~65 MB, 2 files) |
| 5. Memory embeddings | MiniLM-L6-v2 TFLite + vocab.txt | Apache-2.0 | `Madhur-Prakash-Mangal/all-MiniLM-L6-v2-tflite` + `sentence-transformers/all-MiniLM-L6-v2` vocab (~22 MB, 2 files) |
| 6. VAD gate | Silero VAD v5 | MIT | `snakers4/silero-vad` (`src/silero_vad/data/silero_vad.onnx`, ~2.3 MB — the canonical `files/silero_vad.onnx` path 404s) |

Total if the user enables everything: ~455 MB of downloads.

## Online voice (internet, no key)

| Engine | Service | Terms | Endpoint |
|---|---|---|---|
| 7. Online voice (Kokoro-82M) | mesmer.tools TTS API | Free, no key, no signup; 20 generations/hour per IP; text ≤ 2,000 chars/request | `https://mesmer.tools/api/v1/tts` (docs: `https://mesmer.tools/api-tools/tts`) — voices `af_bella` (default, warm American female) and `hf_alpha` (Hindi female, auto-used for Devanagari text); quality `low` (Kokoro, default) / `high` (Azure GPT-audio narrator, falls back to Kokoro on error — the response reports `fellBack`) |

Verified live 2026-11-22: `low` + `af_bella` and `low` + `hf_alpha` (Hindi text) both returned real CDN MP3s (provider kokoro, `fellBack` false); the af_bella MP3 downloads and plays (MPEG layer III, 24 kHz mono, 2.25 s). `high` + `hq_female` worked for a cached text (provider openai, `cached` true) but a fresh high render failed the same day with an Azure OpenAI 530 and fell back to Kokoro — high is an option, never the default. The 20/hour limit is server-enforced per IP; the app keeps a local counter to fail fast and shows the count in Settings. mesmer.tools is a small third-party service — it is never the only voice; the chain always falls back to Pocket, then system TTS.

## Deliberately NOT used

- **Kyutai `kyutai/pocket-tts` (gated safetensors)** — gated ("auto": must
  accept prohibited-use terms with an HF account); cannot be fetched
  anonymously in-app. The sherpa int8 export (CC-BY-4.0, ungated) is
  used instead.
- **Indus Hindi Pocket TTS** (`Indus-Labs/indus-pocket-tts`, CC-BY-4.0)
  — no published ONNX export sherpa-onnx can load. Native Hindi /
  Hinglish on-device voice is a V18 export job (adapt `export_onnx.py`).
- **Maya's models, sounds, art, prompts, endpoints** — never copied.
- **YAMNet** — the laugh detector uses sherpa AudioTagging instead
  (same job, one runtime instead of two).
- **MediaPipe LLM Inference** — maintenance-only since mid-2026 (V15
  decision, still stands).

## Notes

- Pocket TTS has NO Hindi: it speaks English (+ fr/de/pt/it/es) and
  reads Roman Urdu with English pronunciation.
- Moonshine tiny-en is English-only; Urdu/Hindi speech still uses
  Android's SpeechRecognizer.
- Speaker voice-match is a similarity score, NOT biometric
  verification; the profile (one float vector) never leaves the phone
  and is excluded from auto-backup.
