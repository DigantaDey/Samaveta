# SAMAVETA — MODEL CATALOG

**Nothing in this file is downloadable from the app yet.** SAMAVETA ships no weights inside the APK,
and nothing here has been downloaded by this project. Every entry is `CANDIDATE`.

This document and `core/.../model/ModelCatalog.kt` describe the same 27 models, and the code is the
authority: `ModelCatalogTest` asserts the count, the licence flags, the absence of artifact hashes and
the honesty of the status strings. If this file and the code ever disagree, the code is right and
this file is stale.

## Status legend

| Status | Meaning | Downloadable? |
|---|---|---|
| `CANDIDATE` | Named, sourced, assessed on paper. Nothing fetched, nothing run. | No |
| `ARTIFACT_VERIFIED` | Artifact obtained and its SHA-256 recorded from the real distribution. | Yes |
| `BENCHMARKED` | Accuracy, latency and memory measured on a named device. | Yes |
| `APPROVED` | Licence read and confirmed for the intended distribution; runtime verified. | Yes |
| `REJECTED` | Considered and refused, with the reason retained. | No |

`VerificationStatus.allowsDownload` is false for `CANDIDATE` and `REJECTED`, and
`ModelCatalogEntry`'s constructor refuses any entry that claims a verified status without a real
SHA-256. So a status cannot be promoted by editing a string.

## Where the numbers come from

Every figure carries a `FigureSource`, so no number in the UI is bare:

- `DECLARED` — stated by the model's own documentation or by the product owner's brief. Context
  lengths below are declared, not measured.
- `ESTIMATED` — SAMAVETA's own arithmetic from parameter count at 0.55 bytes/parameter (INT4-ish),
  with a 1.35× peak-RAM overhead for KV cache and runtime. Deliberately pessimistic: over-reserving
  produces a refusal the user can read; under-reserving produces an LMKD kill mid-conversation.
  These are **not** measurements, and they are not presented as such.
- `MEASURED` — measured on a named device by this project. **No entry has a `MEASURED` figure yet.**

## Catalog (all `CANDIDATE`)

Sizes are the integer decimal-MB estimate of the weights; peak RAM is weights × 1.35, floored at
64 MB. Both are estimates.

### Agent reasoning / tool use

| Model | Params | Context | Licence | Peak RAM (est.) | Notes |
|---|---|---|---|---|---|
| `Qwen/Qwen3.5-0.8B` | 0.8B | 262,144 | Apache-2.0 | ~594 MB | Preferred agent candidate. Safety-aligned, so it may refuse security-adjacent prompts. |
| `huihui-ai/Huihui-Qwen3.5-0.8B-abliterated` | 0.8B | 262,144 | Apache-2.0 | ~594 MB | Refusal-abliterated. Weaker safety behaviour; recorded as a trade-off, not an upgrade. |
| `meta-llama/Llama-3.2-1B-Instruct` | 1.236B | 131,072 | Llama 3.2 Community | ~918 MB | **Gated.** Custom licence; widest mobile runtime support. |
| `dphn/Dolphin3.0-Llama3.2-1B` | 1.236B | 131,072 | Llama 3.2 Community | ~918 MB | Uncensored fine-tune; inherits Llama obligations. |
| `nztinversive/llama3.2-1b-Uncensored` | 1.236B | 131,072 | Llama 3.2 Community | ~918 MB | Community fine-tune of unvetted provenance. |
| `google/gemma-3-1b-it` | 1.0B | 32,768 | Gemma Terms | ~743 MB | **Gated.** Strong multilingual summarisation; weakest tool-call formatting here. |
| `Qwen/Qwen3-0.6B` | 0.6B | 32,768 | Apache-2.0 | ~446 MB | Toggleable thinking; good fallback when 0.8B will not fit. |
| `google/functiongemma-270m-it` | 0.27B | 32,768 | Gemma Terms | ~201 MB | **Gated.** Purpose-trained for function calling at 270M. |
| `LiquidAI/LFM2-1.2B` | 1.2B | 32,768 | LFM Open | ~891 MB | Fast on CPU, strong prose. Licence review needed. |
| `LiquidAI/LFM2-700M` | 0.7B | 32,768 | LFM Open | ~520 MB | Licence review needed. |
| `LiquidAI/LFM2-350M` | 0.35B | 32,768 | LFM Open | ~260 MB | Preferred micro-router alongside SmolLM2-360M. |
| `HuggingFaceTB/SmolLM2-360M-Instruct` | 0.36B | 8,192 | Apache-2.0 | ~267 MB | Function-calling data; hallucinates on complex schemas, single-step only. |
| `HuggingFaceTB/SmolLM2-135M-Instruct` | 0.135B | 8,192 | Apache-2.0 | ~100 MB | Fragile; only behind strict JSON grammar constraints. |
| `ElementMerc/tinyllama-1.1b-uncensored` | 1.1B | 2,048 | Apache-2.0 | ~817 MB | Legacy Llama-2 architecture; weaker than everything else at this size. |

### Text

The three entries above with `role = TEXT` (`LFM2-1.2B`, `gemma-3-1b-it`, `LFM2-700M`) plus
`tinyllama-1.1b-uncensored` are grouped for chat and rewriting in the UI.

### Code

| Model | Params | Context | Licence | Peak RAM (est.) | Notes |
|---|---|---|---|---|---|
| `Maincode/Maincoder-1B` | 1.0B | 2,048 | Apache-2.0 | ~743 MB | Fill-in-the-middle only; 2K context cannot hold a multi-file task. |
| `fdtn-ai/antares-1b` | 1.0B | 131,072 | Apache-2.0 | ~743 MB | **Gated.** Defensive-security agent loop; listed for completeness, not selected — SAMAVETA ships no vulnerability scanner. |

### OCR

| Model | Params | Licence | Peak RAM (est.) | Notes |
|---|---|---|---|---|
| `lightonai/LightOnOCR-2-1B` | 1.0B | Apache-2.0 | ~743 MB | Preferred document path. Full-page OCR to Markdown with bounding boxes. |
| `openbmb/MiniCPM-OCR-1.0` | 1.3B | Apache-2.0 | ~965 MB | Best ~1B layout parsing, but needs a companion layout model for tables/formulae. |
| `tencent/HunyuanOCR` | 1.0B | Tencent Hunyuan Community | ~743 MB | Licence review required; 4K images mean a large KV cache. |

### Vision

| Model | Params | Context | Licence | Peak RAM (est.) | Notes |
|---|---|---|---|---|---|
| `HuggingFaceTB/SmolVLM2-500M-Video-Instruct` | 0.5B | 8,192 | Apache-2.0 | ~371 MB | Smallest vision path; weak on dense small text. |
| `vikhyatk/moondream2` | 1.86B | 2,048 | Apache-2.0 | ~1,381 MB | Native pointing and bounding boxes; short context. Relevant to UI grounding. |

### Voice

| Model | Params | Context | Licence | Peak RAM (est.) | Notes |
|---|---|---|---|---|---|
| `Qwen/Qwen3-ASR-0.6B` | 0.6B | — | Apache-2.0 | ~446 MB | Preferred ASR. 52 languages/dialects with streaming. |
| `nvidia/parakeet-tdt-0.6b-v3` | 0.6B | — | CC-BY-4.0 | ~446 MB | Fast, timestamps, attribution required. Weaker Indic coverage, which matters here. |
| `hexgrad/Kokoro-82M` | 0.082B | — | Apache-2.0 | ~64 MB | Preferred TTS. >10× real-time on CPU per its own card; no voice cloning. |
| `SparkAudio/Spark-TTS-0.5B` | 0.5B | — | **CC-BY-NC-SA-4.0** | ~371 MB | **Non-commercial.** Personal build only; cannot ship in a distributed release. |
| `OuteAI/Llama-OuteTTS-1.0-1B` | 1.0B | 8,192 | **CC-BY-NC-SA-4.0** | ~743 MB | **Non-commercial.** Voice cloning via GGUF; licensing blocks distribution. |
| `LiquidAI/LFM2.5-Audio-1.5B` | 1.5B | 32,768 | LFM Open | ~1,114 MB | Speech in and out in one model, removing the STT→LLM→TTS cascade. Above 1B and a custom licence. |

## Licence gates that the code enforces

- `ModelCatalog.licenceBlocked()` returns exactly the two CC-BY-NC-SA entries. `releaseSafe()`
  excludes them. `bestFor()` never returns a non-commercial model, even when it is the smallest one
  that fits.
- Gated repositories (`meta-llama/Llama-3.2-1B-Instruct`, `google/gemma-3-1b-it`,
  `google/functiongemma-270m-it`, `fdtn-ai/antares-1b`) cannot be fetched unattended, so a one-tap
  install must open a browser step rather than fail halfway.
- `ModelCatalog.toDescriptor()` **throws** for any entry without a recorded SHA-256. That is the
  integrity gate: a `ModelDescriptor` cannot be constructed from a guess.

## Preferred candidates, for the record

Chosen on licence and size fit for an 8 GB `arm64-v8a` phone, not on measured quality — no
measurements exist:

| Need | Preferred | Why |
|---|---|---|
| ASR | `Qwen/Qwen3-ASR-0.6B` | Apache-2.0, 0.6B, Indic coverage |
| TTS | `hexgrad/Kokoro-82M` | Apache-2.0, 82M, real-time on CPU |
| OCR | `lightonai/LightOnOCR-2-1B` | Apache-2.0, page-level document reading |
| Agent | `Qwen/Qwen3.5-0.8B` | Apache-2.0, sub-1B tool calling |
| Router | `HuggingFaceTB/SmolLM2-360M-Instruct` or `LiquidAI/LFM2-350M` | Apache-2.0 / small; LFM needs licence review |

## Required before any entry becomes `APPROVED`

1. Official source URL and exact revision/commit recorded.
2. Licence text read and the four permissions confirmed (commercial use, redistribution,
   modification, attribution) — see `MODEL_LICENSES.md`.
3. SHA-256 of the exact redistributed artifact recorded in the Model Registry.
4. Accuracy measured on a representative dataset, with the dataset named.
5. Cold-load time, first-token latency, tokens/second and peak RAM measured on the 8 GB reference
   device.
6. Thermal delta and battery cost over a sustained run.
7. Offline behaviour confirmed with the network disabled.
8. Quantised variant compared against the unquantised baseline; accuracy loss recorded.
9. A runtime path for this ABI (llama.cpp, LiteRT-LM or ONNX Runtime Mobile) actually built and loaded
   the artifact on the reference device.

Until all nine exist, an entry stays `CANDIDATE` and cannot be offered for download. See
`MODEL_BENCHMARKS.md` for the measurement methodology — it deliberately contains no numbers.
