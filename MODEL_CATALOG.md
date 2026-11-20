# SAMAVETA — MODEL CATALOG

**Nothing in this file is downloadable from the app yet.** SAMAVETA ships no weights inside the APK.
Entries below are *candidates under evaluation*; a model may only be added to the runtime catalog
after the licensing, integrity and device benchmarks in this document are complete.

## Status legend
`CANDIDATE` (identified, not evaluated) · `EVALUATED` (benchmarked) · `APPROVED` (license + quality
+ device fit verified) · `REJECTED` (with reason)

> **Policy:** SAMAVETA does not train foundational models. It selects, quantises, converts and
> integrates existing open models and mature libraries.

## Language / LLM

| Candidate | Why considered | Size class | License (to verify) | Status |
|---|---|---|---|---|
| Llama 3.2 1B Instruct | Small enough for an 8 GB phone; strong instruction following | ~1B, INT4 ~0.8 GB | Llama 3.2 Community License — commercial use with conditions, needs review | CANDIDATE |
| Gemma 2 2B | Good quality per byte; LiteRT-ready lineage | ~2B, INT4 ~1.4 GB | Gemma Terms of Use | CANDIDATE |
| Qwen2.5 0.5B / 1.5B Instruct | Apache-2.0, clean commercial terms | 0.5–1.5B | Apache-2.0 | CANDIDATE (preferred licence) |
| Phi-3.5-mini | Strong reasoning at 3.8B | ~3.8B, INT4 ~2.3 GB | MIT | CANDIDATE (too large for baseline) |

**Selection constraint (already enforced in code):** a model whose peak RAM exceeds available
memory is refused by `ModelRegistry.evaluate`, and a >1.5 B-parameter model is refused on a
low-RAM device regardless of momentary free memory.

## Embeddings / retrieval

| Candidate | Use | License (to verify) | Status |
|---|---|---|---|
| EmbeddingGemma-class (300M) | Local semantic search, dedupe assistance | Gemma Terms | CANDIDATE |
| all-MiniLM-L6-v2 | Small, fast sentence embeddings | Apache-2.0 | CANDIDATE (preferred) |
| bge-small-en | Retrieval quality at small size | MIT | CANDIDATE |

## OCR

| Candidate | Use | License | Status |
|---|---|---|---|
| ML Kit Text Recognition v2 (on-device) | OCR with bounding boxes; bundled download | Proprietary SDK, free on-device use | CANDIDATE — verify offline behaviour |
| Tesseract (tess-two / Tesseract4Android) | Fully offline, genuinely open | Apache-2.0 | CANDIDATE |
| PaddleOCR mobile | Strong multilingual, larger | Apache-2.0 | CANDIDATE |

**Decision:** prefer a dedicated OCR engine; a general VLM is never used for routine text extraction.

## Object detection / image classification

| Candidate | Use | License | Status |
|---|---|---|---|
| EfficientDet-Lite0/2 (TFLite) | General object detection | Apache-2.0 | CANDIDATE |
| MobileNetV3 (TFLite) | Image classification | Apache-2.0 | CANDIDATE |
| YOLOv8n / YOLO11n | Fast detection | AGPL-3.0 (requires review for commercial use) | CANDIDATE — licence risk |

## Vision-language

| Candidate | Use | License | Status |
|---|---|---|---|
| PaliGemma 2 (3B mix) | Screenshot/document reasoning | Gemma Terms | CANDIDATE |
| Qwen2-VL 2B | VLM reasoning, broader licence | Apache-2.0 | CANDIDATE (preferred) |
| SmolVLM (256M/500M) | Very small VLM for constrained devices | Apache-2.0 | CANDIDATE |

## Speech

| Candidate | Use | License | Status |
|---|---|---|---|
| Whisper tiny/base (whisper.cpp or LiteRT) | Offline ASR | MIT | CANDIDATE |
| Vosk / Kaldi small models | Lightweight ASR, wake-word friendly | Apache-2.0 | CANDIDATE |
| Piper | Offline neural TTS | MIT | CANDIDATE |
| Android `TextToSpeech` engine | Zero-download fallback; multi-language | Platform | CANDIDATE — quality varies by OEM |
| ECAPA-TDNN / WeSpeaker embeddings | Speaker identification | Apache-2.0 / MIT | CANDIDATE |

## Required before any entry becomes APPROVED

1. Official source URL and exact version/commit recorded.
2. Licence text read and the four permissions confirmed (commercial use, redistribution,
   modification, attribution) — see `MODEL_LICENSES.md`.
3. SHA-256 of the exact redistributed artifact recorded in the Model Registry.
4. Accuracy measured on a representative dataset, with the dataset named.
5. Cold-load time, first-token latency, tokens/second and peak RAM measured on the 8 GB reference device.
6. Thermal delta and battery cost over a sustained run.
7. Offline behaviour confirmed with the network disabled.
8. Quantised variant compared against the unquantised baseline; accuracy loss recorded.

Until all eight exist, an entry stays `CANDIDATE` and cannot be offered for download.
