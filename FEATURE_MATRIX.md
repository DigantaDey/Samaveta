# SAMAVETA — FEATURE MATRIX

Status: `NOT_STARTED` · `IN_PROGRESS` · `IMPLEMENTED` (code exists, compiles where possible) ·
`TESTED` (executed tests) · `VERIFIED` (evidence on real hardware) · `BLOCKED`

**No requirement is `VERIFIED`: nothing has run on a physical device.**

| ID | Requirement | Status | Location | Evidence / gap |
|---|---|---|---|---|
| FND-01 | Native Kotlin + Compose app shell | IMPLEMENTED | `app/src/main/kotlin/.../app` | Not compiled (no SDK here); CI builds it |
| FND-02 | Design system (palette, type, spacing, components) | IMPLEMENTED | `ui/design/` | Reviewed; not rendered |
| FND-03 | System/Light/Dark theme, default System, persisted | IMPLEMENTED | `ui/design/Theme.kt`, `ThemeViewModel` | Logic reviewed; needs UI test |
| FND-04 | Branding incl. correct Devanagari समवेत | IMPLEMENTED | `ui/screens/SettingsScreen.kt` (`Text("समवेत")`), `res/values/strings.xml` (`app_name`) | Rendered by Compose, which **compiles and lints clean in CI**; never seen on a device |
| FND-05 | SANTOOR release mark (vector) | IMPLEMENTED | `res/drawable/ic_santoor.xml` | — |
| FND-06 | Dependency injection, navigation, CI | IMPLEMENTED | `di/`, `ui/SamavetaRoot.kt`, `.github/workflows` | CI unrun (Actions permission unavailable) |
| LAYA-01 | Real published contract, reconciled | TESTED | `core/.../laya/v1/LayaV1Contract.kt` | `LayaV1Test`; source cited in file header |
| LAYA-02 | Client: timeout, bounded retry, admission, error taxonomy | TESTED | `LayaV1Client.kt` | 422 never retried; 503 retried once; state only CONNECTED after a real call |
| LAYA-03 | Strict response decoding (reject whole, never partially trust) | TESTED | `LayaV1Codec.kt` | 10 rejection cases |
| LAYA-04 | Deterministic answer policy (abstention, truncation, floors) | TESTED | `LayaQuestions.kt` | model cannot remove mandatory confirmation |
| LAYA-05 | Transport/codec against a real `laya-serve` | TESTED | `core/src/test/resources/laya-live/` | Replayed from a running server: prediction, batch, health, 400, 401, 422×2, 500; `LayaLiveContractTest` (15) |
| LAYA-06a | A successful prediction end to end | TESTED (fixture checkpoint only) | `core/.../laya/v1/LayaQuestions.kt` | A real `200` was captured from LAYA's offline fixture server and is decoded, gated and refused correctly. Quality on real weights remains unproven. |
| LAYA-06c | Prediction quality on real LAYA weights | **BLOCKED** | — | Weight download unreachable from this environment |
| LAYA-08 | Production HTTP transport | TESTED | `laya/v1/LayaUrlTransport.kt` | `LayaTransportTest` (12) against a loopback socket: no redirects, byte cap, timeout, bearer header, https-only |
| LAYA-09 | Agent turn with honest outcomes | TESTED | `laya/v1/LayaAgent.kt` | `LayaAgentTest` (13): proposal/clarify/refusal/unavailable, never claims execution |
| LAYA-10 | Endpoint + token configuration | IMPLEMENTED (compiles in CI) | `app/data/LayaSettingsRepository.kt`, Settings screen | Token in Keystore-backed encrypted prefs; https enforced; not run on a device |
| LAYA-11 | App wired to the v1 client | IMPLEMENTED (compiles in CI) | `di/AppModule.kt`, `AgentScreen.kt` | Real `LayaUrlTransport` wired from stored settings; compiles and lints clean, Hilt graph validated by `hiltJavaCompileDebug` in CI |
| LAYA-06b | `laya-java` on-device (offline LAYA) | **BLOCKED** | — | Upstream: "Not implemented yet: … Android" |
| LAYA-07 | PROPOSED planner contract | SUPERSEDED | `core/.../laya/LayaContract.kt` | Not LAYA's interface; wired to nothing |
| MOD-01 | Model Registry with full metadata | TESTED | `model/ModelDescriptor.kt`, `ModelAdmission.kt` | invariant tests |
| MOD-02 | Admission: RAM/storage/accelerator/ABI/integrity | TESTED | `ModelRegistry.evaluate` | incl. 3GB device refusal |
| MOD-03 | Secure download with SHA-256 gate | TESTED | `app/.../ModelDownloader.kt` | `ModelDownloaderTest` (11) |
| MOD-04 | Model runtime (LiteRT-LM etc.) | NOT_STARTED | — | No runtime integrated |
| MOD-05 | Model catalog (27 candidates) with verification gating | TESTED | `model/ModelCatalog.kt` | `ModelCatalogTest` (17); all `CANDIDATE`, no artifact hashes |
| MOD-07 | Model catalog with real artifacts | NOT_STARTED | — | Nothing downloadable (`MODEL_CATALOG.md`) |
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
| QLT-01 | Unit tests executed | TESTED | local harness + CI | 474/474 (408 core + 66 app) in both |
| QLT-02 | Lint + static analysis in CI | IMPLEMENTED | CI `lintDebug` | unrun here |
| QLT-03 | Instrumentation / UI tests | NOT_STARTED | — | — |
| QLT-04 | Physical-device verification | NOT_STARTED | — | **blocks any VERIFIED status** |
| REL-01 | Debug/release build, signing | IMPLEMENTED (config) | `app/build.gradle.kts` | no keystore supplied |
| REL-02 | Licensing registry verified | IN_PROGRESS | `MODEL_LICENSES.md` | pending per-model confirmation |
