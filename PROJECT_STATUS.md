# SAMAVETA — PROJECT STATUS

**Product:** SAMAVETA (समवेत) — *Intelligence, United.*
**First major release:** SAMAVETA 1.0 — **SANTOOR**
**Last updated:** 2026-11-20
**Build state:** `core` + app pure-logic **compile and pass 357/357 unit tests** (executed locally).
Android UI layer: **written, not yet compiled** — see *Verification environment* below.

---

## 1. Honest summary

This repository currently contains a **verified domain core** and a **written Android application
layer**. It is not a shippable product yet, and this document does not pretend otherwise.

What is real and proven:

- A pure-Kotlin `core` module containing the security, LAYA-integration, memory, information-intelligence,
  device-health, model-admission, browser-policy and action-orchestration logic. Every one of
  those subsystems has executed unit tests
  (357 tests, all passing) run with a real JVM.
- Android project scaffolding that configures the toolchain correctly (AGP 8.7.3 / Kotlin 2.0.21 /
  Compose BOM 2024.12.01 / Hilt / Room / DataStore / WorkManager, minSdk 28, arm64-only).
- A completed design system and brand identity, including the SANTOOR release mark.

What is **not** real yet, and must not be described as working:

- The Compose UI has never been compiled (no Android SDK in this environment) or run on a device.
- No model runtime is integrated. No model is downloadable. `Models` is empty by design.
- No voice, vision/OCR or Android capability *integration* exists; the browser and capability
  policy layers are tested, but nothing is connected to a WebView, an SMS manager or a listener.
- LAYA is **not connected**: no contract has been supplied. The adapter is `NOT_CONFIGURED`.
- No APK has been produced or installed.

## 2. Verification environment (why some claims are absent)

This workspace deliberately cannot resolve the Android toolchain: outbound network is limited to
GitHub/pypi/npm, so Maven Central, Google Maven and `services.gradle.org` are unreachable, and no
JDK or Android SDK is preinstalled.

To avoid simply *asserting* that code works, the project carries its own verification harness
(`tools/jvm-verify/`) which compiles the modules with a standalone Kotlin compiler and executes the
JUnit-style tests. That is the source of the 357/357 result. See `docs/DEVELOPMENT_ENVIRONMENT.md`.

**Consequence:** the Android layer's correctness is *reviewed*, not *executed*, in this environment.
CI (`.github/workflows/verify.yml`) is the mechanism that closes that gap — it builds, lints and
unit-tests the Android module and produces a debug APK.

## 3. Phase status

| Phase | Scope | Status | Evidence |
|---|---|---|---|
| 0 | Repository + feasibility audit | **VERIFIED** | `docs/DECISIONS.md` D-001…D-003 |
| 1 | Native foundation (structure, design system, theme, DI, CI) | **IMPLEMENTED** (UI not compiled) | `app/src/main/kotlin/.../ui/design`, CI workflow |
| 2 | LAYA integration | **BLOCKED** for production; adapter + validator IMPLEMENTED and TESTED | `LayaContractTest`, `LayaDecisionValidatorTest` |
| 3 | Model infrastructure | **PARTIAL**: registry, admission, downloader verified; no runtime | `ModelAdmission`, `ModelDownloaderTest` |
| 4 | Visual perception (OCR/objects/VLM) | **NOT_STARTED** | — |
| 5 | Voice (ASR/TTS/Voice Lab/speaker ID) | **NOT_STARTED** | — |
| 6 | Android capabilities | **PARTIAL**: authorization + action pipeline + plan runner TESTED; no Android integration | `SecurityTest`, `ActionPipelineTest`, `PlanRunnerTest` |
| 7 | Agent browser | **PARTIAL**: policy/session/reader core TESTED; no WebView integration | `BrowserTest` |
| 8 | Information intelligence | **TESTED** through fetch → parse → digest; not yet wired to the UI | `IntelligenceTest`, `FeedParserTest`, `IntelligenceCoordinatorTest`, `HttpFeedFetcherTest` |
| 9 | Memory and learning | **TESTED** (model + persistence codec; no Room wiring yet) | `MemoryTest`, `PersistenceTest` |
| 10 | Integration and optimisation | **NOT_STARTED** | — |
| 11 | SANTOOR release candidate | **NOT_STARTED** | — |

Status vocabulary is the directive's: `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED`, `TESTED`,
`VERIFIED`, `BLOCKED`. `IMPLEMENTED` means the code exists and compiles where it can be compiled.
`TESTED` means executed tests cover it. `VERIFIED` additionally requires evidence on real hardware.

## 4. Test evidence

Executed locally by `tools/jvm-verify/verify.sh`:

```
Tests run: 357, Passed: 357, Failed: 0, Skipped: 0
VERDICT: PASS
```

| Suite | Tests | What it pins down |
|---|---|---|
| `JsonTest` | 11 | Untrusted JSON parsing: duplicate keys, control chars, precision, error positions |
| `LayaContractTest` | 13 | Wire contract, correlation, unknown version/step rejection, retry policy, deadline |
| `LayaDecisionValidatorTest` | 19 | No unvalidated model/capability can execute; pending-vs-unauthorized; confirmation forcing |
| `SecurityTest` | 20 | Deny-by-default, emergency stop, single-use confirmation, no false success |
| `MemoryTest` | 26 | Feedback weighting, damping, decay, sticky mutes, store caps, vector math |
| `IntelligenceTest` | 24 | Dedupe guards, attribution, coverage limitations, honest dates |
| `ModelDownloaderTest` | 11 | HTTPS-only, traversal safety, SHA-256 gate, atomic install |
| `StructuredOutputTest` | 22 | No lenient parsing of model output; injection detection |
| `TaskStateMachineTest` | 16 | Process recovery, no duplicate side effects, retry policy |
| `ModelResourceManagerTest` | 31 | Memory budget, eviction, in-use refusal, thermal shedding, failure memo |
| `BrowserTest` | 36 | Scheme/host policy, userinfo spoofing, stale refs, challenge suspension, reader mode |
| `ActionPipelineTest` | 26 | Deny/confirm/execute/verify/audit ordering, expiry, no false success |
| `PlanRunnerTest` | 23 | Plan escalation rejection, per-step honesty, cancellation at step boundaries |
| `PersistenceTest` | 24 | Versioned codecs, corrupt-record isolation, mid-run recovery |
| `FeedParserTest` | 19 | Hostile RSS/Atom input, entity handling, absent dates stay unknown |
| `IntelligenceCoordinatorTest` | 21 | Partial vs total source failure, ETags, muted topics |
| `HttpFeedFetcherTest` | 15 | https-only, no redirects, 304 vs 3xx, content-type and size limits |

These are *unit* tests. There are **no** instrumentation tests, **no** UI tests and **no**
physical-device results yet; `TEST_REPORT.md` records that gap explicitly.

## 5. Known blockers

1. **LAYA contract unavailable** — no production interface supplied. Documented contract +
   adapter + mock exist; production integration is BLOCKED. (`docs/LAYA_INTEGRATION.md`)
2. **No Android SDK / Maven access in this environment** — Android compilation is deferred to CI.
3. **No model distribution rights or model artifacts** — nothing downloadable yet;
   `MODEL_CATALOG.md` lists candidates with licensing status pending verification.
4. **No physical device runs** — every performance, thermal, battery and accelerator claim is
   unproven. The app reports `UNKNOWN` for GPU/NPU rather than asserting support.

## 6. Privacy and security posture (current state)

- Local-first: no analytics, no telemetry, no crash reporting SDK, no embedded API keys.
- Cloud backup and device transfer disabled for all app data.
- Capabilities denied by default; `CRITICAL` capabilities can never be persistently granted.
- SUCCEEDED can only be produced with confirmed evidence. Timeouts report `UNCONFIRMED`.
- Model downloads are HTTPS-only and SHA-256-verified before they become usable.

## 7. Next actions

1. Get CI green: compile the Android module, fix the inevitable Compose/DI compile errors.
2. Integrate one real runtime (LiteRT-LM) behind `ModelRuntime`, with a downloadable, checksummed
   small text model, then replace `Models` placeholder with a real manager screen.
3. Implement persistence (Room) for memory, audit log and installed models.
4. Obtain LAYA's real contract and reconcile `LayaWireContract`.
5. Only then: voice, perception, browser, capabilities — in that order.
