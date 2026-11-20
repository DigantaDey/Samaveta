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

_No model has been approved. Candidates are listed in `MODEL_CATALOG.md`; each requires a completed
record here before it can be offered for download._

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
