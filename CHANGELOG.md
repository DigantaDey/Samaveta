# CHANGELOG

All notable changes to SAMAVETA. Semantic versioning for minor/patch; each major release is named
after a musical instrument.

## [Unreleased] — 1.0.0-SANTOOR (release candidate: not shippable)

### Added

**Domain core (`:core`, pure Kotlin, 302 unit tests) and app pure logic (55 tests)**
- `json` — dependency-free JSON parser/writer that rejects duplicate keys, control characters,
  trailing content and malformed numbers, and reports precise error positions. Integers keep their
  literal so 64-bit values survive parsing.
- `device` — `DeviceProfile`, `MemoryTier`, accelerator availability (including an honest `UNKNOWN`),
  and `DeviceHealthPolicy` that defers background work on thermal/memory/battery pressure while
  keeping the interactive request of a waiting user alive.
- `model` — `ModelDescriptor` with mandatory licence, checksum and RAM/peak-RAM data;
  `ModelRegistry.evaluate` admitting or refusing a model against installation state, integrity,
  accelerator evidence, ABI, peak RAM, storage and device class.
- `laya` — versioned, explicitly PROPOSED wire contract with strict decoding; `LayaAdapter` owning
  timeouts, deadline enforcement, bounded retry and the error taxonomy; `LayaDecisionValidator`
  that re-checks every proposed model and capability before execution.
- `security` — deny-by-default capability engine, emergency stop, single-use target-bound
  confirmations, bounded audit log that refuses credential-like detail, and `ActionReport` where
  success is unrepresentable without confirmed evidence.
- `memory` — episodic/semantic/procedural memories with provenance; feedback model where explicit
  signals outweigh inferred ones, weights are damped and decay, and mutes are sticky; explainable
  relevance ranking; cosine/top-k vector math.
- `intelligence` — deduplication guarded by a numeric signature, entity extraction labelled by
  method and confidence, honest date handling, and a digest builder that requires provenance and
  states coverage limitations.
- `modelruntime` — strict structured-output validation (never "repairs" JSON) and a prompt-injection
  detector covering instruction override, role impersonation, credential exfiltration, capability
  escalation, tool-call smuggling and hidden text.
- `core` — task state machine with process-recovery semantics: interrupted tasks need an explicit
  decision, unconfirmed effects are never auto-retried, resume skips verified steps.
- `modelruntime` — `ModelResourceManager` with a memory budget: duplicate acquires share one handle,
  large models evict idle ones first, in-use models are never evicted, thermal pressure sheds
  prefetched then idle models, and failed loads are memoised against retry storms.
- `browser` — `BrowserPolicy` that decides navigation and interaction before a page is touched:
  dangerous schemes blocked, custom schemes handed to the user, loopback/private/LAN hosts refused,
  https required for agent navigation, allowlists matched host-or-subdomain only (no userinfo
  spoofing), password fields never filled, verification challenges suspending all interaction, and
  generation-scoped element refs that go stale on navigation. `ReaderMode` removes boilerplate
  sentence-by-sentence and truncates at sentence boundaries.
- `security` — `ActionPipeline`, the single sanctioned path from a proposed action to a user-facing
  outcome: authorize → confirm → execute → verify → audit. Success is unrepresentable without
  confirmed evidence, and a denial never reaches the executor.
- `persistence` — versioned JSON codecs for memories, the audit log and tasks. Corrupt records are
  isolated and reported, a newer schema version is refused rather than guessed, and a task stored
  mid-run is flagged for recovery instead of being resumed.
- `orchestration` — `PlanRunner` executes a LAYA plan under SAMAVETA's authority. Undeclared or
  unknown capabilities reject the plan before anything runs; each step is reported honestly, skipped
  steps are reported as NOT_RUN, and cancellation stops at a step boundary.

**Intelligence ingestion (`:app`, pure logic, compiled and tested by the JVM harness)**
- Defensive RSS/Atom parser treating every feed as hostile input: per-entry rejection, markup
  stripping, bounded entities, entry and size caps, and dates that stay unknown when absent.
- `IntelligenceCoordinator` that reports partial failure honestly — a failed source is named, and a
  total failure says "failed refresh" rather than presenting an empty digest as a quiet day.
- https-only feed transport with no redirect following, content-type and byte limits, and 304
  handled as success.

**Android app (`:app`, not yet compiled — see `PROJECT_STATUS.md`)**
- Gradle/AGP/Kotlin/Compose/Hilt/Room/DataStore/WorkManager configuration, version catalog,
  minSdk 28, arm64-only, release signing via environment variables only.
- Design system: warm neutral palette with a single copper accent, type scale, spacing/radius
  tokens, and shared empty/error/skeleton/status components.
- SANTOOR release mark as a vector drawable, plus adaptive launcher and splash icons.
- Screens: Today, Agent, Activity, Intelligence, Memory, Models, Voice Lab, Browser, Automation,
  Settings. Implemented-vs-unavailable is stated honestly on each.
- `ModelDownloader`: HTTPS-only, path-traversal-safe, SHA-256-verified before install, deletes
  mismatched artifacts; backed by a visible data-sync foreground service.
- Theme selection persisted in DataStore; automation pause and emergency stop wired to preferences.

**Build, CI and documentation**
- `.github/workflows/verify.yml`: domain tests, Android build/lint/unit tests, and release guards.
- `tools/jvm-verify/verify.sh`: offline harness that compiles and executes the domain tests with a
  standalone Kotlin compiler (because this environment cannot reach Maven Central).
- `tools/check/repo-hygiene.py`: enforced boundaries (Android-free core, no binaries, no mock LAYA in
  release sources, no unearned VERIFIED claims, no import rot).
- Documentation set: status, architecture, feature matrix, requirements traceability, LAYA
  integration, model catalog and licensing registry, privacy, design system, test report, known
  limitations, development environment.

### Fixed
- A later positive feedback signal silently un-muted a topic the user had muted.
- Deduplication merged distinct stories that differed only by a number ("Android 16" → "Android 17").
- The decision validator treated a not-yet-approved capability as unauthorized, which would have
  blocked every first request a user ever made.
- Model-download validation threw instead of returning a failure, risking a caller treating a
  refusal as success.
- Structured-output schema paths were unprefixed in nested validation, misreporting failing fields.
- **Security:** a granted confirmation never expired. `ConfirmationBroker.grant()` removed the
  pending request that `consume()` read the deadline from, so an approval stayed valid forever.
  Expiry now travels with the `ConfirmationGrant` and is asserted by a regression test.
- `ActionPipeline` required an approval token that no component could legitimately mint, so every
  confirmation-gated action was unreachable. Approvals now go through `ActionPipeline.approve()`.
- The feed fetcher's redirect branch swallowed HTTP 304, turning every cheap conditional refresh
  into a reported failure. 304 is now checked first.

### Known gaps
See `KNOWN_LIMITATIONS.md`. In short: no Android build yet, no model runtime, LAYA not connected, no
voice/vision/capability integrations (their policy layers are tested, their Android integrations are
not written), and nothing verified on a physical device.
