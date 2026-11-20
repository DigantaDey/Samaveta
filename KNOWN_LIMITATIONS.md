# SAMAVETA — KNOWN LIMITATIONS

This file is deliberately blunt. Anything listed here must not be presented to a user or a
stakeholder as working.

## Blocking (must be resolved before any release)

1. **The Android app has never been compiled.** No Android SDK was available in the development
   environment. Compose code may contain compile errors; CI is the first real build.
2. **No model runtime is integrated.** There is no inference of any kind. `Models` is empty.
3. **LAYA is not connected, and cannot be offline on a phone.** Its real contract is reconciled — it is
   a typed decision engine, not a planner — and the client is tested against its documented payloads,
   but no live server has been contacted. LAYA's own Java SDK lists Android as not implemented, and the
   only shipped deployment is an HTTP server, so an agent narrative driven by LAYA needs a network
   connection. Planning, model routing and
   autonomous execution are unavailable; the app says so in Today, Agent and Settings.
4. **No device verification.** Every performance, thermal, battery and accelerator claim is
   unproven. GPU/NPU are reported `UNKNOWN`.
5. **No APK / signed release.** Release signing requires keystore material supplied out-of-band.
6. **CI has not been executed** in this environment (no permission to run workflows). Its
   configuration is validated as YAML only.

## Functional gaps

| Feature | Reality |
|---|---|
| Voice (ASR, TTS, Voice Lab, speaker ID) | Not implemented. Voice Lab shows a status screen only. |
| Vision (OCR, objects, VLM) | Not implemented. |
| Agent Browser | Not implemented. Human-verification handoff — a hard requirement — does not exist. |
| Android capabilities (notifications, messaging, calls, files, calendar) | Not implemented. The authorization engine exists and is tested; nothing performs actions. |
| Information ingestion | Pipeline is implemented and tested, but nothing fetches RSS or web pages. No live digest. |
| Memory persistence | In-memory model only. No Room database; nothing survives process death. |
| Audit persistence | `AuditLog` is in-memory and bounded; Activity is empty until Room lands. |
| Preferences | DataStore-backed (theme, pause, emergency stop); no encrypted storage yet. |
| Search / embeddings | Cosine + top-k implemented; no embedding model, so no semantic search. |
| Voice wake word | Not started; background-listening promises are explicitly out of scope. |

## Behavioural limitations

- **Chat is not useful yet.** The Agent replies that it cannot plan, because it cannot. This is
  intentional honesty, not a placeholder to be removed before the engine exists.
- **`System` theme** follows the system, including battery-saver dark mode; this is unverified on
  device because the UI has not been run.
- **Model admission** is conservative: a model can be refused for RAM even when the device might
  manage it, because LMKD killing the app is worse than a clear refusal.
- **Deduplication** relies on title similarity + numbers. It will merge some genuinely distinct
  stories with near-identical wording, and may miss duplicates with rewritten headlines. Cluster
  review is a UI requirement still outstanding.
- **Entity extraction** is lexicon + capitalisation only; it will miss entities not in the seed
  lexicon and marks heuristic matches as uncertain by design.
- **Interest damping** means a user must give several signals before ranking changes visibly;
  deliberate, but it will feel slow at first.
- **`src/test` for `:app`** only covers Android-free logic (the downloader). ViewModels and screens
  have no tests yet.

## Platform constraints (not bugs)

- `minSdk 28`, `arm64-v8a` only. 32-bit and pre-Android-9 devices are not supported.
- Thermal APIs are reported only on API 29+; below that, thermal status is treated as nominal and
  the app relies on memory/battery policy instead.
- No AccessibilityService-based automation, by policy. Some third-party app automation is therefore
  permanently out of scope on stock Android.
- `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE` and AD_ID are deliberately not requested.

## Documentation gaps

- `MODEL_BENCHMARKS.md` is a methodology document; it contains **no measured results**, because no
  model has been run. It exists so that the missing measurements are a recorded state rather than an
  omission, and it defines the promotion gate from `CANDIDATE` to `EVALUATED`.
- `REQUIREMENTS.md` tracks the directive; traceability to tests exists only for the implemented areas.
- No user guide or release notes yet: writing them before the product works would produce
  instructions that cannot be followed.
