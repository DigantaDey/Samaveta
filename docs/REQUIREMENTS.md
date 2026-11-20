# SAMAVETA — REQUIREMENTS TRACEABILITY

Traces the product directive to implementation. Full per-feature status lives in
`FEATURE_MATRIX.md`; this file records the *binding constraints* and where each one is enforced.

## Part 1 — Product vision & identity

| ID | Requirement | Enforced at | Status |
|---|---|---|---|
| R-1.1 | One coherent platform, not a set of tools | `ui/SamavetaRoot.kt`, shared design system | IMPLEMENTED |
| R-2.1 | Branding: SAMAVETA / समवेत / Intelligence, United. | `res/values/strings.xml` | IMPLEMENTED (rendering unverified) |
| R-2.2 | LAYA is external; SAMAVETA owns the platform | `core/laya/` boundary; no planner in `core` | TESTED (boundary), BLOCKED (integration) |
| R-2.3 | Release named after an instrument | `SAMAVETA 1.0 — SANTOOR`, `ic_santoor.xml` | IMPLEMENTED |

## Part 2 — Technical foundations

| ID | Requirement | Enforced at | Status |
|---|---|---|---|
| R-3.1 | Native Android, Kotlin, Compose | `app/build.gradle.kts` | IMPLEMENTED (not compiled here) |
| R-3.2 | Clean architecture, modular, testable | `:core` / `:app` split, CI guard | TESTED |
| R-3.3 | No dependency added merely for popularity | `DECISIONS.md` D-002, D-003 | IMPLEMENTED |
| R-3.4 | Core AI works without an external AI API | Local-first posture | IMPLEMENTED (no runtime yet) |

## Part 3 — LAYA

| ID | Requirement | Enforced at | Status |
|---|---|---|---|
| R-4.1 | Dedicated integration module with adapter/contract/mapping | `core/laya/` | TESTED |
| R-4.2 | Do not recreate LAYA internally | No planning logic anywhere in repo | TESTED (guard test + review) |
| R-4.3 | Validate LAYA's model selection; never silently change it | `LayaDecisionValidator` | TESTED |
| R-4.4 | Integration status visible; no false operational claim | `LayaIntegrationState`, `LayaIntegrationGuard` | TESTED |
| R-4.5 | Obtain the real interface | — | **BLOCKED** |

## Part 4 — Model sourcing policy

| ID | Requirement | Enforced at | Status |
|---|---|---|---|
| R-5.1 | Do not train foundational models | Policy doc; no training code | IMPLEMENTED |
| R-5.2 | ≥3 alternatives compared before integration | `MODEL_CATALOG.md` | IN_PROGRESS (identified, not benchmarked) |
| R-5.3 | Licensing documented incl. commercial/redistribution | `LicenseInfo`, `MODEL_LICENSES.md` | TESTED (enforcement), PENDING (entries) |
| R-5.4 | Registry fields mandatory (incl. SHA-256) | `ModelDescriptor` invariants | TESTED |

## Part 5 — Model infrastructure

| ID | Requirement | Enforced at | Status |
|---|---|---|---|
| R-6.1 | Registry + admission (RAM/storage/thermal/accelerator) | `ModelRegistry.evaluate` | TESTED |
| R-6.2 | Downloads verified, resumable, removable | `ModelDownloader` | TESTED |
| R-6.3 | Device-aware operation, graceful degradation | `DeviceProfile`, `MemoryTier`, `DeviceHealthPolicy` | TESTED |
| R-6.4 | Lazy load / unload / one model at a time | — | NOT_STARTED |

## Parts 6–9 — Voice, vision, browser, information

| ID | Requirement | Status |
|---|---|---|
| R-7.x | Voice platform, Voice Lab, speaker ID with consent | NOT_STARTED |
| R-8.x | OCR, detection, VLM with structured output | NOT_STARTED |
| R-9.x | Browser with DOM extraction and human-verification handoff | NOT_STARTED |
| R-10.1 | Ingestion → dedupe → entities → ranking → digest | TESTED (pipeline), NOT_STARTED (fetching) |
| R-10.2 | Source attribution, dates, coverage limitations, no fabrication | TESTED |
| R-10.3 | Feedback controls (11 signals) update personalisation | TESTED |

## Parts 10–13 — Memory, capabilities, execution, UI

| ID | Requirement | Enforced at | Status |
|---|---|---|---|
| R-11.1 | Three memory kinds with provenance | `memory/MemoryModel.kt` | TESTED |
| R-11.2 | Explicit feedback > inferred; no overfitting; recency decay | `InterestModel` | TESTED |
| R-12.1 | Narrowest Android API; document platform constraints | `KNOWN_LIMITATIONS.md`, AndroidManifest comment | IMPLEMENTED (no capabilities yet) |
| R-12.2 | Never report a message sent without evidence | `ActionVerifier` | TESTED |
| R-13.1 | Deterministic capability authorisation, audit, emergency stop | `security/` | TESTED |
| R-14.1 | Minimal, non-generic visual identity; System default theme | `ui/design/`, `docs/DESIGN_SYSTEM.md` | IMPLEMENTED (unrendered) |
| R-14.2 | All ten primary screens exist | `ui/screens/` | IMPLEMENTED (6 report subsystem status) |

## Parts 14–20 — Performance, security, QA

| ID | Requirement | Enforced at | Status |
|---|---|---|---|
| R-15.1 | No UI-thread inference (when inference exists) | — | NOT_STARTED |
| R-15.2 | Performance budgets measured | — | NOT_STARTED |
| R-16.1 | Encrypted sensitive data, Keystore | dependency declared | NOT_STARTED |
| R-16.2 | No embedded secrets, no telemetry | dependency set; CI secret guard | TESTED (guard) |
| R-16.3 | No silent cloud fallback | architecture; no remote inference path | IMPLEMENTED |
| R-20.1 | Requirement → implementation → test → evidence | `FEATURE_MATRIX.md` | ONGOING |
| R-20.2 | No VERIFIED without device evidence | Status rules | ENFORCED — zero VERIFIED |

## Release blockers (Part 22) — current position

| Blocker | Position |
|---|---|
| Builds cleanly | **Unknown** — never compiled |
| Critical crashes | Unknown — never run |
| Core functions only use mock data | Avoided: unimplemented features report status instead of mocks |
| Model integrity verifiable | Yes — SHA-256 gate enforced and tested |
| Unauthorized actions can execute | No — deny-by-default, tested |
| Tasks falsely marked successful | No — type-enforced, tested |
| Theme behaviour consistent | Unverified |
| Advertised-but-unavailable functionality | Avoided in UI copy; LAYA/voice/vision/browser stated as unavailable |
| Main workflows tested on real hardware | **No** |
| Play Store requirements reviewed | Partially (permissions rationale drafted); full review outstanding |
| Release signing | Configured, no keystore supplied |
| Licensing obligations | Models unresolved; dependencies audited at a high level |
