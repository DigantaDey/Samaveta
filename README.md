<div align="center">

# SAMAVETA

### समवेत

**Intelligence, United.**

A local-first, safety-first personal AI agent for Android.
First major release: **SAMAVETA 1.0 — SANTOOR**

[![Verify](https://github.com/DigantaDey/Samaveta/actions/workflows/verify.yml/badge.svg)](https://github.com/DigantaDey/Samaveta/actions/workflows/verify.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-28-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey)

</div>

---

## The one-sentence version

**LAYA proposes; SAMAVETA decides.** A model's opinion is never sufficient authority to touch a
phone. Every action passes through a deny-by-default capability registry, a single-use confirmation
broker, an evidence-checked verifier and an audit log — and the reasoning is in
[`ARCHITECTURE.md`](ARCHITECTURE.md), not just in a diagram.

## Honest status — read this before anything else

This project's rule is that nothing is described as working until it is. That makes this section the
most important one in the file.

| Area | State | Evidence |
|---|---|---|
| Domain logic (`:core`, pure Kotlin) | **408 unit tests executed and passing** | `./tools/jvm-verify/verify.sh`, `docs/TEST_REPORT.md` |
| LAYA integration | **Real transport + real agent turn, tested against a live `laya-serve`** | `LayaV1Test` (44), `LayaLiveContractTest` (15), `LayaTransportTest` (12), `LayaAgentTest` (13) |
| LAYA decision *quality* | **Unmeasured** — only a randomly-initialised fixture checkpoint was ever reachable | `KNOWN_LIMITATIONS.md` §3 |
| Model catalog | **27 candidates, all `CANDIDATE`, nothing downloadable** | `MODEL_CATALOG.md`, `ModelCatalogTest` (17) |
| Android UI (`:app`) | **Compiles, lints clean (0 findings) and passes 66 unit tests in CI; has never been run on a device** | `.github/workflows/verify.yml`, `docs/TEST_REPORT.md` |
| Capability execution | **Not implemented** — the agent says "nothing was executed", because nothing is | `LayaAgent.Turn.Proposed` |
| Voice, vision, browser, Room persistence | **Not implemented** | `FEATURE_MATRIX.md` |
| Anything `VERIFIED` on hardware | **Nothing. There is no physical-device evidence at all.** | `docs/TEST_REPORT.md` §1 |

Two consequences worth stating plainly:

- **Chat is honest, not useful.** Against any checkpoint this project can run, the agent refuses
  rather than guessing. That is the designed behaviour, not a placeholder.
- **No number in this repository is a measurement.** Performance, thermal, battery and accuracy
  figures do not exist anywhere, including in [`MODEL_BENCHMARKS.md`](MODEL_BENCHMARKS.md), which is
  deliberately a methodology document.

## Contents

- [Try it now](#try-it-now)
- [Get the APK](#get-the-apk)
- [How it works](#how-it-works)
- [The request path](#the-request-path)
- [Safety model](#safety-model)
- [Building](#building)
- [Testing](#testing)
- [Release signing and CI](#release-signing-and-ci)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [Principles the code enforces](#principles-the-code-enforces)

## Try it now

There is no installable release yet: a signed APK requires out-of-band signing material, and the
agent cannot execute actions in any case. What you *can* run today is the part that is proven:

```bash
git clone https://github.com/DigantaDey/Samaveta.git
cd Samaveta

# Needs only a JDK 17+ and kotlinc — no Android SDK, no Gradle, no network.
./tools/jvm-verify/verify.sh
#   Tests run: 474, Passed: 474, Failed: 0, Skipped: 0
#   VERDICT: PASS

# Static checks over the Android layer (resource refs, Hilt bindings, manifest classes).
python3 tools/check/repo-hygiene.py
python3 tools/check/android-preflight.py
```

To exercise the LAYA path end to end against a real server, see
[`LAYA_INTEGRATION.md`](LAYA_INTEGRATION.md) §6 — it uses LAYA's own offline fixture server, so no
model download is required.

## Get the APK

Every push to `main` or an `arena/**` branch builds and publishes an **unsigned debug APK**:

1. Open the **Actions** tab → the **Verify** workflow → the newest green run.
2. Download the **`samaveta-debug-apk`** artifact (≈18 MB, retained 14 days).
3. `adb install -r samaveta-debug-apk/app-debug.apk`

The debug build carries the `.debug` application-id suffix, so it installs alongside a release build.
It has not been run on a physical device by this project — see the status table above.

A **signed release APK** is produced only when the four signing secrets exist; see
[Release signing and CI](#release-signing-and-ci). The release job refuses to emit an unsigned APK
rather than call one a release.

## How it works

SAMAVETA is two layers with a hard boundary between them.

**`:core` — pure Kotlin, zero Android imports.** This is where every decision with consequences
lives: the capability registry, the confirmation broker, the action pipeline, the LAYA contract and
codes, the model registry and admission gate, memory and interest modelling, the information
pipeline, and the browser policy. Because it has no Android dependency, it runs on a plain JVM — which
is why it can be executed in this sandbox and in CI without an emulator.

**`:app` — Android framework, Compose UI, Hilt, DataStore.** This layer is deliberately thin and
deliberately dumb: it renders state and forwards intents. It is not allowed to be the place where a
safety rule lives, because a safety rule in a screen is a safety rule someone can forget.

The boundary is not a convention. `repo-hygiene.py` and CI both fail the build on
`import android.*` inside `core`, so the pure layer cannot quietly acquire a framework dependency.

## The request path

```
utterance
  → LayaQuestions.state(...)              bounded, explicit question set
  → LayaUrlTransport                      https-only, no redirects, byte-capped, timeouts
  → LAYA /v1/systemone                    typed answers + calibrated probabilities
  → LayaV1Codec.decodePrediction          strict: unknown shapes are rejected, never partly trusted
  → LayaQuestions.read(...)               SAMAVETA's policy, not the model's opinion:
                                            truncated / collapsed evidence → refuse
                                            abstained or unevaluated      → ask the user
                                            below the capability floor    → ask the user
                                            otherwise                     → proposal
  → CapabilityAuthorizer                  deny by default; emergency stop wins over every grant
  → ConfirmationBroker                    single-use, target-bound, expiring
  → ActionExecutor                        (not implemented in this build)
  → ActionVerifier                        SUCCEEDED is unrepresentable without confirmed evidence
  → AuditLog                              bounded, refuses credential-like detail
```

Only the last four stages can touch the device, and every one of them is deterministic Kotlin with
tests. A model cannot authorise anything: it can only make a proposal that the pipeline then
re-examines.

## Safety model

| Claim | How it is enforced | Where it is tested |
|---|---|---|
| Deny by default | Every capability starts unauthorised | `SecurityTest` |
| Critical capabilities are never permanently granted | Grant persistence is refused for `CRITICAL` | `SecurityTest` |
| Emergency stop overrides everything | Checked before every grant | `SecurityTest` |
| Confirmation is single-use and target-bound | A grant is consumed and bound to a target | `SecurityTest` |
| No false success | `ActionReport.succeeded` requires confirmed evidence | `SecurityTest` |
| A model cannot remove a confirmation | `mutatesState` forces it regardless of the answer | `LayaAgentTest`, `PlanGateTest` |
| Lost evidence refuses first | Truncation, dropped tokens and collapsed options all refuse | `LayaLiveContractTest` |
| Model output is never "repaired" | Strict structured-output validation | `StructuredOutputTest` |
| Prompt injection is detected, not obeyed | Six injection classes | `StructuredOutputTest` |
| A model that does not fit is refused | Peak-RAM admission, not file size | `ModelResourceManagerTest`, `ModelCatalogTest` |
| A missing checksum is fatal | `ModelDescriptor` cannot exist without a SHA-256 | `ModelCatalogTest`, `ModelDownloaderTest` |
| No green status without a real call | `LayaV1State.CONNECTED` only after a 2xx, and a 401 downgrades it | `LayaTransportTest` |

## Building

### Requirements

| Tool | Version | Why |
|---|---|---|
| JDK | 17+ (**21 recommended**) | Bytecode targets 17; Gradle 8.9 will not run on a JDK newer than 21 |
| Android SDK | platform 35, build-tools 35.0.0 | `compileSdk`/`targetSdk` |
| Gradle | 8.9 (via `./gradlew`) | Pinned in `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | 2.0.21 (via Gradle) | Pinned in `gradle/libs.versions.toml` |

The wrapper is the canonical one and is committed in full (`gradlew` +
`gradle/wrapper/gradle-wrapper.jar`), so a clean checkout needs no local Gradle installation.

### Commands

```bash
./gradlew verify                    # core tests + app unit tests + lint (fails fastest first)
./gradlew :core:test                # domain logic only — no Android SDK required
./gradlew :app:assembleDebug        # debug APK → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # release APK; needs signing env vars (see below)
./gradlew :app:lintDebug            # warnings are errors in this project
```

`local.properties` with `sdk.dir=/path/to/Android/sdk` is needed for the Android module when
`ANDROID_HOME` is unset. It is gitignored and must never be committed.

### Build configuration worth knowing

- `minSdk 28`, `targetSdk 35`, `arm64-v8a` only. 32-bit and pre-Android-9 are out of scope, and the
  release APK is checked to make sure no other ABI sneaks in.
- Lint and Kotlin warnings are **errors**. A deprecation warning fails the build, by design.
- `android:allowBackup="false"` and `dataExtractionRules` exclude all app data from backup and device
  transfer; the release APK guard verifies this survived packaging.

## Testing

Four layers, each catching what the one below cannot:

| Layer | Command | Count | Runs where |
|---|---|---|---|
| Local harness (core + Android-free app code) | `./tools/jvm-verify/verify.sh` | 474 (408 + 66) | Any JDK, no Android SDK, no network |
| Real Gradle build | `./gradlew verify` | 474 (408 + 66) | CI, and developer machines with the Android SDK |
| Android compile + lint | `:app:compileDebugKotlin`, `:app:lintDebug` | 0 findings | CI (needs the Android SDK) |
| APK assembly + integrity | `:app:assembleDebug`, then `aapt2 dump badging` | — | CI |
| Static guards | `repo-hygiene.py`, `android-preflight.py` | — | CI, every push |
| Instrumentation / device | *not written* | 0 | — |

CI's last green run reported, exactly: `core unit tests: tests 408, failures 0, errors 0, skipped 0,
suites 19` and `:app unit tests: tests 66, failures 0, errors 0, skipped 0, suites 4`. Those numbers
are produced by parsing the JUnit XML the build wrote, so a suite that silently does not run is a
failure, not a pass.

The offline harness exists because this sandbox cannot resolve the Android toolchain (no Maven
Central, no SDK). Rather than assert that code works, it compiles the pure modules with a standalone
Kotlin compiler and executes the tests through a JUnit-compatible shim. See
[`docs/DEVELOPMENT_ENVIRONMENT.md`](docs/DEVELOPMENT_ENVIRONMENT.md).

Two of the more useful guards:

- **`tools/check/android-preflight.py`** — resolves resource references, `R.*` symbols, manifest
  component classes and **Hilt bindings** statically. It found a real `@ApplicationContext` omission
  that would have failed the Android build.
- **`tools/check/release-apk-guards.py`** — inspects the built release APK for `debuggable`, the
  application id, `allowBackup` and the shipped ABI set before it is allowed to become a release.

## Release signing and CI

CI (`.github/workflows/verify.yml`) runs on every push and pull request, and adds a release job on a
`v*` tag or a manual dispatch with `release: true`.

**Jobs:** `Domain logic (JVM)` · `Android build, lint and unit tests` · `Release blockers` ·
`Signed release APK`.

Every job mirrors failures into check-run annotations, so a red build can be diagnosed from the
Checks tab without downloading a log.

### Producing a release APK

1. Generate a keystore **outside** this repository:

   ```bash
   keytool -genkeypair -v -keystore samaveta-release.jks -alias samaveta \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add four repository secrets (**Settings → Secrets and variables → Actions**):

   | Secret | Value |
   |---|---|
   | `SAMAVETA_KEYSTORE_BASE64` | `base64 -w0 samaveta-release.jks` |
   | `SAMAVETA_KEYSTORE_PASSWORD` | keystore password |
   | `SAMAVETA_KEY_ALIAS` | `samaveta` |
   | `SAMAVETA_KEY_PASSWORD` | key password |

3. Either push a tag (`git tag v1.0.0-rc1 && git push origin v1.0.0-rc1`) or run the workflow manually
   with **release** checked.

The job refuses to continue without the keystore rather than emitting an unsigned artifact and
calling it a release. It then verifies the APK's v2 signature with `apksigner` and runs the APK
guards before uploading. A tag publishes a GitHub Release; a manual run keeps the APK as a workflow
artifact only.

## Project layout

```
Samaveta/
├── core/                        pure Kotlin domain layer — no android.* (enforced)
│   └── src/main/kotlin/com/samaveta/android/core/
│       ├── laya/v1/             the real LAYA contract: codec, client, transport, agent, policy
│       ├── model/               model descriptor, registry, admission, 27-model catalog
│       ├── security/            capability registry, confirmation broker, action pipeline, audit
│       ├── orchestration/       plan gate and runner (device validation × authority)
│       ├── memory/              interests, feedback weighting, ranking, vector math
│       ├── intelligence/        ingestion, dedupe, entity extraction, digests
│       ├── browser/             navigation policy, session, reader mode
│       ├── device/              profile, health policy, accelerators
│       └── json/                dependency-free strict JSON
├── app/                         Android layer — Compose, Hilt, DataStore
│   └── src/main/kotlin/com/samaveta/android/
│       ├── ui/                  screens, design system
│       ├── data/                preferences, encrypted LAYA settings
│       ├── di/                  Hilt modules
│       ├── intelligence/        feed transport and coordinator (Android-free)
│       └── models/              downloader, foreground service
├── tools/
│   ├── jvm-verify/              offline JVM test harness
│   └── check/                   repo hygiene, Android pre-flight, APK guards
├── docs/                        test report, privacy, requirements, design system
└── .github/workflows/verify.yml CI
```

## Documentation

| Document | Purpose |
|---|---|
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | Honest current state, test evidence, blockers |
| [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) | What does not work, plainly — read with the status |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Modules, request path, boundaries |
| [`FEATURE_MATRIX.md`](FEATURE_MATRIX.md) | Every requirement with status and location |
| [`LAYA_INTEGRATION.md`](LAYA_INTEGRATION.md) | Contract, transport, agent, reconciliation checklist |
| [`MODEL_CATALOG.md`](MODEL_CATALOG.md) | The 27 candidates and their verification status |
| [`MODEL_LICENSES.md`](MODEL_LICENSES.md) | Licence registry; non-commercial and gated models |
| [`MODEL_BENCHMARKS.md`](MODEL_BENCHMARKS.md) | Measurement methodology — deliberately no results |
| [`DECISIONS.md`](DECISIONS.md) | Why things are the way they are |
| [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) | Executed tests and their results |
| [`docs/PRIVACY.md`](docs/PRIVACY.md) | Every network path, every protection |
| [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) | Requirement → implementation traceability |
| [`docs/DEVELOPMENT_ENVIRONMENT.md`](docs/DEVELOPMENT_ENVIRONMENT.md) | Why the offline harness exists |

## Principles the code enforces

- **Deny by default.** No capability runs without a grant; critical capabilities can never be
  persistently granted.
- **No false success.** `SUCCEEDED` is impossible without confirmed evidence; timeouts are
  `UNCONFIRMED`.
- **No false connection.** A green status requires a real authenticated response — an unauthenticated
  health check is not proof that a token works.
- **No false attribution.** A story summarised from a headline says so; a missing date stays unknown.
- **No unverified hardware claims.** GPU/NPU are `UNKNOWN` until a probe proves otherwise.
- **No invented numbers.** Every figure carries `DECLARED`, `ESTIMATED` or `MEASURED`, and there are
  no `MEASURED` figures yet.
- **No silent cloud.** There is no remote inference path at all; the only network calls are a
  LAYA endpoint the user configures, feeds the user adds, and downloads the user starts.

## Licence

The repository contains no model weights and no third-party assets. Model candidates carry their own
licences, recorded per model in [`MODEL_LICENSES.md`](MODEL_LICENSES.md); two of them
(`SparkAudio/Spark-TTS-0.5B`, `OuteAI/Llama-OuteTTS-1.0-1B`) are **non-commercial** and may not ship
in a distributed build.
