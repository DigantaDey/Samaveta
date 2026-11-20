# SAMAVETA — FEATURE MATRIX

Status: `NOT_STARTED` · `IN_PROGRESS` · `IMPLEMENTED` (code exists, compiles where possible) ·
`TESTED` (executed tests) · `VERIFIED` (evidence on real hardware) · `BLOCKED`

**No requirement is `VERIFIED`: nothing has run on a physical device.**

| ID | Requirement | Status | Location | Evidence / gap |
|---|---|---|---|---|
| FND-01 | Native Kotlin + Compose app shell | IMPLEMENTED | `app/src/main/kotlin/.../app` | Not compiled (no SDK here); CI builds it |
| FND-02 | Design system (palette, type, spacing, components) | IMPLEMENTED | `ui/design/` | Reviewed; not rendered |
| FND-03 | System/Light/Dark theme, default System, persisted | IMPLEMENTED | `ui/design/Theme.kt`, `ThemeViewModel` | Logic reviewed; needs UI test |
| FND-04 | Branding incl. correct Devanagari समवेत | IMPLEMENTED | `res/values/strings.xml` | Rendering unverified on device |
| FND-05 | SANTOOR release mark (vector) | IMPLEMENTED | `res/drawable/ic_santoor.xml` | — |
| FND-06 | Dependency injection, navigation, CI | IMPLEMENTED | `di/`, `ui/SamavetaRoot.kt`, `.github/workflows` | CI unrun (Actions permission unavailable) |
| LAYA-01 | Documented integration contract | TESTED | `core/.../laya/LayaContract.kt` | `LayaContractTest` (13) |
| LAYA-02 | Adapter: timeout, retry, cancellation, errors | TESTED | `LayaAdapter.kt` | `LayaContractTest` |
| LAYA-03 | Strict response decoding (no guessing) | TESTED | `LayaWireContract.decodeResponse` | unknown version/step/id-mismatch rejected |
| LAYA-04 | Deterministic decision validation | TESTED | `LayaDecisionValidator.kt` | `LayaDecisionValidatorTest` (16) |
| LAYA-05 | Real production integration | **BLOCKED** | — | No contract supplied by LAYA's developer |
| MOD-01 | Model Registry with full metadata | TESTED | `model/ModelDescriptor.kt`, `ModelAdmission.kt` | invariant tests |
| MOD-02 | Admission: RAM/storage/accelerator/ABI/integrity | TESTED | `ModelRegistry.evaluate` | incl. 3GB device refusal |
| MOD-03 | Secure download with SHA-256 gate | TESTED | `app/.../ModelDownloader.kt` | `ModelDownloaderTest` (11) |
| MOD-04 | Model runtime (LiteRT-LM etc.) | NOT_STARTED | — | No runtime integrated |
| MOD-05 | Model catalog with real artifacts | NOT_STARTED | — | Licensing pending (`MODEL_CATALOG.md`) |
| MOD-06 | Thermal/battery/memory-aware scheduling | TESTED (policy) | `device/DeviceHealth.kt` | policy only; not wired to WorkManager |
| VIS-01 | OCR pipeline | NOT_STARTED | — | — |
| VIS-02 | Object detection | NOT_STARTED | — | — |
| VIS-03 | VLM image reasoning | NOT_STARTED | — | — |
| VOICE-01 | Offline ASR | NOT_STARTED | — | — |
| VOICE-02 | TTS + voice selection | NOT_STARTED | — | — |
| VOICE-03 | Voice Lab UI | NOT_STARTED (status screen only) | `ui/screens/StatusScreens.kt` | Reports unavailability honestly |
| VOICE-04 | Speaker identification | NOT_STARTED | — | — |
| BRW-01 | Browser navigation/interaction policy | TESTED | `browser/AgentBrowser.kt` | `BrowserTest` (36); no WebView wiring |
| BRW-02 | Human-verification handoff | TESTED (policy) | `BrowserSession` | any challenge suspends interaction; UI handoff not built |
| BRW-03 | Reader mode / bounded page context | TESTED | `ReaderMode` | sentence-scoped boilerplate removal, sentence-boundary truncation |
| SEC-07 | Action pipeline: authorize→confirm→execute→verify→audit | TESTED | `security/ActionPipeline.kt` | `ActionPipelineTest` (26) |
| ORC-01 | LAYA plan execution under SAMAVETA authority | TESTED | `orchestration/PlanRunner.kt` | `PlanRunnerTest` (23); rejects undeclared capabilities |
| ORC-02 | Both validators enforced before any step runs | TESTED | `orchestration/PlanGate.kt` | `PlanGateTest` (13) |
| INT-01 | Deduplication | TESTED | `intelligence/InformationPipeline.kt` | 4 dedupe tests |
| INT-02 | Entity extraction with provenance | TESTED | `EntityExtractor` | method + confidence asserted |
| INT-03 | Date handling without fabrication | TESTED | `Dates` | unknown stays unknown |
| INT-04 | Digest with mandatory attribution | TESTED | `intelligence/Digest.kt` | coverage limitation enforced |
| INT-05 | Live source ingestion (RSS/web fetch) | TESTED (pure logic) | `app/.../intelligence/` | parser + coordinator + https transport; no UI wiring |
| INT-06 | Honest partial-failure reporting | TESTED | `IntelligenceRun.statusLine()` | total failure never rendered as a quiet day |
| MEM-01 | Episodic/semantic/procedural memory | TESTED | `memory/MemoryModel.kt` | kinds, provenance, caps |
| MEM-02 | Feedback-driven interests (11 signals) | TESTED | `InterestModel` | explicit≫inferred, damping, decay |
| MEM-03 | Mute honoured absolutely | TESTED | `InterestModel` | sticky until explicit unmute |
| MEM-04 | Preference inspection/edit/export/reset | IMPLEMENTED (API) | `MemoryStore` | No UI yet |
| MEM-05 | Persistence (Room) | IN_PROGRESS | `persistence/Persistence.kt` | versioned codecs TESTED; Room wiring not written |
| SEC-01 | Deny-by-default capability engine | TESTED | `security/CapabilityAuthorization.kt` | 20 tests |
| SEC-02 | Emergency stop / pause automation | TESTED | `CapabilityAuthorizer` | emergency wins over grants |
| SEC-03 | Single-use, target-bound confirmation | TESTED | `ConfirmationBroker` | replay + redirect refused |
| SEC-04 | Audit log with redaction | TESTED | `AuditLog` | refuses credential-like detail |
| SEC-05 | No false success | TESTED | `ActionVerifier` | type-enforced |
| SEC-06 | Encrypted storage via Keystore | NOT_STARTED | — | dependency declared only |
| CAP-01 | Notification/messaging/calendar/files capabilities | NOT_STARTED | — | authorization layer ready |
| QLT-01 | Unit tests executed | TESTED | `build/jvm-verify` | 370/370 |
| QLT-02 | Lint + static analysis in CI | IMPLEMENTED | CI `lintDebug` | unrun here |
| QLT-03 | Instrumentation / UI tests | NOT_STARTED | — | — |
| QLT-04 | Physical-device verification | NOT_STARTED | — | **blocks any VERIFIED status** |
| REL-01 | Debug/release build, signing | IMPLEMENTED (config) | `app/build.gradle.kts` | no keystore supplied |
| REL-02 | Licensing registry verified | IN_PROGRESS | `MODEL_LICENSES.md` | pending per-model confirmation |
