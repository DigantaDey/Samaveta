# SAMAVETA — MODEL AND LIBRARY LICENSING REGISTRY

Two rules govern this file:

1. *Open-weight* is not *open-source*, and neither implies *commercially usable*. Each permission is
   recorded separately.
2. No model or library enters a commercial release until every field below is filled from the
   primary source. "Probably fine" is not a licence status.

The code models this: `LicenseInfo.isReleaseSafe()` requires **commercial use** *and*
**redistribution**. `ModelRegistry.releaseSafe()` filters on it.

## Field template

```
Model / library:
Version / commit:
Official source (URL):
License identifier:
Commercial use:            yes / no / conditional (state condition)
Redistribution:            yes / no
Modification:              yes / no
Attribution required:      yes (+ exact text) / no
Patent grant / clause:     yes / no / none stated
Third-party licences:      (list, or none)
Artifact SHA-256:
Verified by / on:
```

## Application dependencies (declared in `gradle/libs.versions.toml`)

These are build-time dependencies only; no model is bundled. Each must be audited before release
with `./gradlew dependencies` and a licence report.

| Dependency | License | Notes |
|---|---|---|
| Kotlin stdlib, kotlinx-coroutines | Apache-2.0 | Permissive |
| AndroidX (core, lifecycle, activity, navigation, compose, room, datastore, work, webkit, security-crypto) | Apache-2.0 | Permissive |
| Jetpack Compose (BOM) | Apache-2.0 | Permissive |
| Hilt / Dagger | Apache-2.0 | Permissive |
| JUnit 4 | EPL-1.0 | Test-only; not distributed |
| MockK | Apache-2.0 | Test-only |
| Robolectric | MIT | Test-only |
| Turbine | Apache-2.0 | Test-only |

**Not permitted without explicit review:** AGPL/SSPL components (e.g. some YOLO variants) in a
distributed build; any model whose licence restricts commercial use (e.g. CC-BY-NC weights).

## Model entries

_No model has been approved. All 27 candidates are listed in `MODEL_CATALOG.md` and encoded in
`core/.../model/ModelCatalog.kt`; each requires a completed record here before it can be offered for
download. The 27 candidates fall into seven licence families (Apache-2.0, Llama 3.2 Community, Gemma Terms, LFM
Open, Tencent Hunyuan Community, CC-BY-4.0, CC-BY-NC-SA-4.0); two of them — the non-commercial family
and the bespoke ones — cannot be shipped without either a licence decision or an attribution plan, and
gating is a separate access constraint that cuts across families:_

| Group | Models | Consequence |
|---|---|---|
| **Non-commercial** (CC-BY-NC-SA-4.0) | `SparkAudio/Spark-TTS-0.5B`, `OuteAI/Llama-OuteTTS-1.0-1B` | Cannot ship in a distributed build. `ModelCatalog.licenceBlocked()` returns exactly these, and no device pick may return them. |
| **Custom licence, review required** (7) | LFM Open: `LiquidAI/LFM2-1.2B`, `LiquidAI/LFM2-700M`, `LiquidAI/LFM2-350M`, `LiquidAI/LFM2.5-Audio-1.5B`. Gemma Terms: `google/gemma-3-1b-it`, `google/functiongemma-270m-it`. Tencent Hunyuan Community: `tencent/HunyuanOCR` | Terms must be read and recorded per model. Commercial use is not assumed. |
| **Gated download** | `meta-llama/Llama-3.2-1B-Instruct`, `google/gemma-3-1b-it`, `google/functiongemma-270m-it`, `fdtn-ai/antares-1b` | Cannot be fetched unattended; a one-tap install must hand off to a browser step. The Llama 3.2 license also adds acceptable-use and naming obligations, and Dolphin/TinyLlama-NZT fine-tunes inherit them. |
| **Apache-2.0** | 14: `Qwen/Qwen3.5-0.8B`, `huihui-ai/Huihui-Qwen3.5-0.8B-abliterated`, `Qwen/Qwen3-0.6B`, `Qwen/Qwen3-ASR-0.6B`, `HuggingFaceTB/SmolLM2-360M-Instruct`, `HuggingFaceTB/SmolLM2-135M-Instruct`, `HuggingFaceTB/SmolVLM2-500M-Video-Instruct`, `lightonai/LightOnOCR-2-1B`, `openbmb/MiniCPM-OCR-1.0`, `vikhyatk/moondream2`, `hexgrad/Kokoro-82M`, `Maincode/Maincoder-1B`, `fdtn-ai/antares-1b`, `ElementMerc/tinyllama-1.1b-uncensored` | Cleanest terms; retain the licence and NOTICE. |
| **Llama 3.2 Community** | `meta-llama/Llama-3.2-1B-Instruct`, `dphn/Dolphin3.0-Llama3.2-1B`, `nztinversive/llama3.2-1b-Uncensored` | Custom licence with acceptable-use and naming obligations; commercial use conditional. Fine-tunes inherit the base model's obligations. |
| **CC-BY-4.0** | `nvidia/parakeet-tdt-0.6b-v3` | Commercial use permitted with attribution; attribution text must be surfaced in the app. |

### Pending verification — Llama 3.2 1B Instruct
```
Official source:       https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct (to confirm at time of use)
License identifier:    Llama 3.2 Community License
Commercial use:        conditional — permitted below 700M MAU, with attribution and acceptable-use terms
Redistribution:        to confirm with licence text and Meta's redistribution terms
Attribution:           "Llama 3.2 is licensed under the Llama 3.2 Community License, Copyright © Meta Platforms, Inc."
Status:                PENDING — not approved, not downloadable
```

### Pending verification — Qwen2.5 0.5B/1.5B Instruct
```
Official source:       https://huggingface.co/Qwen (to confirm exact repo)
License identifier:    Apache-2.0
Commercial use:        yes
Redistribution:        yes
Attribution:           retain licence and notices
Status:                PENDING — preferred option on licence grounds; still requires SHA-256 + benchmarks
```

### Pending verification — Tesseract / Tesseract4Android (OCR)
```
License identifier:    Apache-2.0
Commercial use:        yes
Redistribution:        yes
Status:                PENDING — engine only; language data files carry separate licences and must be listed
```

### Pending verification — Piper (TTS) and voice data
```
License identifier:    MIT (engine)
Voice models:          per-voice licences differ and must be recorded individually
Status:                PENDING — no voice model may ship until its own licence is recorded
```

## Voice and speaker data policy

- A voice profile may only be created from a speaker who has consented, on the device, for that use.
- Importing a voice model requires recording its provenance and licence.
- Reproducing an identifiable person's voice from unauthorised recordings is prohibited, regardless
  of technical feasibility.
- Speaker embeddings never leave the device and are excluded from backups (see
  `app/src/main/res/xml/data_extraction_rules.xml`).
