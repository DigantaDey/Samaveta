# SAMAVETA — MODEL BENCHMARKS

**Status of this document: methodology only. It contains no measured results.**

`KNOWN_LIMITATIONS.md` is explicit about why: no model has been downloaded, no runtime has been
integrated, and no physical device has been available to this project. Every number a benchmark
would produce — cold-load time, first-token latency, tokens/second, peak RAM, thermal delta, battery
drain, ASR word-error rate, OCR accuracy — is therefore **unknown**. This file must stay empty of
numbers until each measurement is actually taken, with the device, build and dataset recorded beside
it. Fabricated or extrapolated figures are worse than missing ones, because they get believed.

> **Rule:** a row may only be filled in by copying output from a run whose command, device and
> revision are recorded in the same row. "Should be about right" is not a benchmark.

---

## 1. Why benchmarks gate the catalog

`MODEL_CATALOG.md` lists candidates as `CANDIDATE` until nine conditions are met, and five of them are
measurements. The ladder is implemented, not just documented: `VerificationStatus` in
`model/ModelCatalog.kt` (`CANDIDATE` → `ARTIFACT_VERIFIED` → `BENCHMARKED` → `APPROVED`, plus
`REJECTED`) refuses to construct an entry that claims a verified status without a real SHA-256. The catalog therefore cannot promote a model without this document. The direction
of the dependency matters: benchmarks do not exist to produce a nice table, they exist to refuse
models that would make SAMAVETA unpleasant or unusable on the devices it claims to support.

Two failure modes this guards against:

- **A model that fits in storage but not in memory.** A 4-bit 3B model is ~2 GB on disk and needs
  more than that resident with KV cache and tokenizer overhead. `ModelRegistry.evaluate` refuses on
  *peak* RAM, not on file size, and this document is where the peak figure is allowed to come from.
- **A model that is fine for ten seconds and painful for ten minutes.** Phone SoCs throttle. A
  first-token latency measured on a cold, idle device says almost nothing about sustained use, which
  is what a conversation actually is.

## 2. Reference devices

Benchmarks are meaningless without naming the hardware. SAMAVETA targets three tiers; the same model
must be measured on each tier it claims to support.

| Tier | Representative class | RAM | Why it is in the matrix |
|---|---|---|---|
| `BASELINE` | Mid-range 2021–2022 SoC, 6–8 GB | 6–8 GB | The floor we promise to work on. Smallest models only. |
| `REFERENCE` | Current upper-mid SoC, 8–12 GB | 8–12 GB | The device every published number is quoted against. |
| `HIGH` | Flagship with a real NPU and vendor delegate | 12 GB+ | Where accelerator delegates are worth claiming. |

The exact model names, Android versions and SoC revisions are recorded in the run log (section 8)
rather than guessed here. A tier with no recorded device produces no published number.

## 3. What is measured, and how

### 3.1 Large language models (text)

| Metric | Definition | Method | Acceptable |
|---|---|---|---|
| Cold load | Process start → first successful generation | Stop the app, clear it from recents, measure to first token | ≤ 8 s REFERENCE |
| First-token latency | Prompt submitted → first token emitted | Median of 20 runs, 128-token prompt | ≤ 2.5 s REFERENCE |
| Decode rate | Tokens/second over tokens 32–288 | Same 20 runs, discard first 32 tokens | ≥ 12 tok/s REFERENCE |
| Peak RSS | Max resident set during a 512-token generation | `dumpsys meminfo` sampling plus in-process `Debug.getMemoryInfo` | ≤ 60% of tier RAM |
| Sustained 10-min drift | Decode rate at minute 10 ÷ rate at minute 1 | Continuous generation, thermal log alongside | ≥ 0.70 |
| Thermal delta | Battery temperature change over that 10 minutes | `BatteryManager` at start and end | ≤ 8 °C |
| Battery cost | mAh consumed over the 10-minute run | `BatteryManager` counters | recorded, no threshold |
| Quality | Task accuracy on the model's eval set | Named dataset, fixed prompt set | ≥ baseline, no regression from quantisation |
| Quantisation loss | Accuracy delta INT4 vs FP16 | Same dataset, same prompts | ≤ 2 points absolute |

**Instruction-following quality is measured, not assumed.** A model that decodes fast but cannot hold
SAMAVETA's JSON contracts is worse than a slower one, because every structured output is validated
strictly and a model that fails validation is a model that does nothing.

### 3.2 Embeddings

| Metric | Method | Acceptable |
|---|---|---|
| Encode latency | 512-token chunk, median of 100 | ≤ 40 ms REFERENCE |
| Retrieval quality | Recall@10 on a named retrieval set | ≥ 0.85 |
| Memory | Resident size with 10k vectors cached | ≤ 200 MB |

### 3.3 OCR

| Metric | Method | Acceptable |
|---|---|---|
| Character accuracy | Named document set, printed and photographed | ≥ 0.95 printed, ≥ 0.85 photographed |
| Latency | 1080×2400 screenshot | ≤ 600 ms REFERENCE |
| Layout handling | Columns, tables, mixed script | recorded pass/fail per case |
| Offline | Airplane mode run | mandatory pass |

Screenshots are the primary input — a screenshot is clean, rendered text, so a poor result on
screenshots is disqualifying regardless of camera performance.

### 3.4 Vision-language models

| Metric | Method | Acceptable |
|---|---|---|
| Screenshot Q&A accuracy | Fixed 50-question set over screenshots | ≥ 0.80 |
| Latency | One image + 64-token answer | ≤ 6 s REFERENCE |
| Peak RSS | During image encode + generation | ≤ 70% of tier RAM |
| Refusal honesty | Questions about unreadable content | must say unknown, not guess |

The last row is the one that matters most for this product: a VLM that invents screen contents is a
safety problem, not a quality problem.

### 3.5 Speech recognition

| Metric | Method | Acceptable |
|---|---|---|
| Word error rate | Named read-speech set and a spontaneous-speech set | ≤ 12% read, ≤ 20% spontaneous |
| Real-time factor | Audio seconds ÷ processing seconds | ≤ 0.5 |
| Streaming latency | Speech end → final transcript | ≤ 400 ms |
| Memory | Resident during streaming | ≤ 300 MB |
| Language coverage | Each claimed language measured separately | per-language WER |
| Offline | Airplane mode | mandatory pass |

Indic-language coverage is measured per language and never inferred from English results.

### 3.6 Text to speech

| Metric | Method | Acceptable |
|---|---|---|
| Time to first audio | Text submitted → first audio frame | ≤ 300 ms |
| Real-time factor | Audio produced ÷ wall clock | ≥ 2.0 |
| Intelligibility | Blind transcription of 20 generated sentences | ≥ 95% word match |
| Fallback | Android `TextToSpeech` engine present and selected | recorded |

### 3.7 Object detection / classification

| Metric | Method | Acceptable |
|---|---|---|
| mAP / top-1 | Named dataset, quantised build | recorded, compared to published baseline |
| Latency | 640×640 (detection), 224×224 (classification) | ≤ 60 ms / ≤ 15 ms REFERENCE |
| Delegate parity | CPU vs GPU vs NPU outputs on the same 100 images | agreement ≥ 0.98 |

Delegate parity is its own gate because vendor delegates are the usual source of silently wrong
output on obscure devices.

## 4. Conditions that must accompany every published number

A row is invalid unless all of the following are recorded with it:

1. Device model, SoC, RAM, Android build, thermal state at start (cool).
2. App revision (git SHA) and the exact model artifact SHA-256.
3. Quantisation and runtime (LiteRT-LM, MediaPipe, whisper.cpp, vendor SDK) with version.
4. Whether an accelerator delegate was active, and which.
5. Charging state, screen brightness, airplane mode where relevant.
6. The command or script that produced the number.

Numbers without these are anecdotes. They are allowed in a scratch note and not in this file.

## 5. Reproducibility

The harness is expected to live in `tools/bench/` and to emit machine-readable results so a table can
be regenerated rather than retyped. Until that harness exists, this document deliberately stays a
methodology: writing a table by hand invites transcription errors and accidental invention.

## 6. What is blocked today, and why

| Blocker | Consequence |
|---|---|
| No model runtime integrated | Nothing to measure; no load, decode or memory figures |
| No model artifacts or licences cleared | Cannot download, cannot redistribute, cannot benchmark. All 27 catalog entries sit at `CANDIDATE` with no artifact hash. |
| No physical device in this environment | Thermal, battery and sustained-drift rows impossible |
| No named eval datasets selected | Quality rows would have no denominator |

These are recorded, not hidden. `MODEL_BENCHMARKS.md` appears in the required-documentation list
precisely so that "we have not measured it" is a visible state rather than an omission.

## 7. Promotion rule

A catalog entry moves `CANDIDATE → ARTIFACT_VERIFIED` only when a real distribution has been fetched
and its SHA-256 recorded, `ARTIFACT_VERIFIED → BENCHMARKED` only when sections 3.1–3.7 have rows for it
on at least the `REFERENCE` tier, and `BENCHMARKED → APPROVED` only when the licence conditions in
`MODEL_LICENSES.md` are confirmed and the offline run has passed. No exceptions, and no promotion on
the strength of a vendor's published benchmark: vendor numbers are a screening filter, not evidence
about this app on these devices.

## 8. Run log

Empty. The first entry will be added when a model and a device are both available.

| Date | Device | Model + SHA | Runtime | Result file |
|---|---|---|---|---|
| — | — | — | — | — |
