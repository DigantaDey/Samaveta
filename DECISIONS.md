# DECISIONS

Architecture decisions with their reasons. Superseded entries are kept.

## D-001 — Pure-Kotlin `core` module with a hard no-Android rule
**Decision.** All trust-critical logic (authorization, validation, verification, memory, dedupe,
model admission, JSON) lives in `:core` with no `android.*`/`androidx.*` imports.
**Why.** It is the only way to *execute* that logic in this environment and in CI without an
emulator, and it prevents accidental coupling of policy to the platform layer.
**Enforcement.** CI job `release-guards` fails on any Android import in `core/src/main`.

## D-002 — Ship our own small JSON codec
**Decision.** Do not depend on kotlinx.serialization for `core`.
**Why.** LAYA's wire contract must be mapped field-by-field with validation and precise errors;
model manifests and feeds are untrusted input. A 350-line, fully tested parser also keeps `core`
dependency-free so it can be compiled by the offline verifier.
**Cost.** No compile-time serializer generation; mapping code is hand-written and tested.
**Revisit when** the contract is stable and mapping code outweighs the parser.

## D-003 — `minSdk 28`, `arm64-v8a` only
**Decision.** Refuse 32-bit and pre-Android-9 devices.
**Why.** On-device inference runtimes, Keystore-backed crypto and thermal APIs are only dependable
from API 28; 32-bit devices cannot host the models the product is built around. Shipping a build
that technically installs but cannot run a model would violate "never advertise unavailable
capabilities".
**Cost.** Excludes a slice of low-end devices — acceptable given the 8 GB/128 GB baseline.

## D-004 — LAYA is integrated through SAMAVETA-owned transport, never reimplemented
**Decision.** `LayaTransport` is an interface SAMAVETA owns; the wire schema is explicitly marked
PROPOSED until LAYA's developer supplies the real one.
**Why.** The directive forbids inventing LAYA's interface or building a competitor internally.
**Consequence.** `NOT_CONFIGURED` is the shipping state; the Agent screen says so instead of faking.

## D-005 — Modelling false success as unrepresentable
**Decision.** `ActionReport` throws in its initializer if `status == SUCCEEDED` without
`ActionEvidence.Confirmed`. Timeouts map to `UNCONFIRMED`, never `SUCCEEDED`.
**Why.** "Never report a successful action without verifying that it actually happened" is easier to
keep when the type system refuses the alternative.

## D-006 — Deny-by-default capabilities; CRITICAL can never be persistent
**Decision.** Every capability starts `DENIED`. A `CRITICAL` capability requested as `PERSISTENT` is
downgraded to `SESSION` and forced to per-use confirmation.
**Why.** A single careless tap must not leave an agent able to spend money or change security
settings indefinitely.

## D-007 — Interest model: explicit ≫ inferred, damped, decaying, sticky mutes
**Decision.** Explicit feedback ×3; weight delta scaled by `1/√n`; 30-day half-life; mute only
liftable via explicit `unmute()`.
**Why.** Prevents one tap defining a user, prevents runaway weights, and makes "not interested"
actually mean it. All four behaviours are unit-tested (a test caught a bug where a positive signal
silently un-muted a topic).

## D-008 — Deduplication guarded by a numeric signature
**Decision.** Titles only count as the same event if their numeric tokens match.
**Why.** A test caught "Android 16 preview" being merged into "Android 17 preview". Numbers in
headlines are almost always the distinguishing fact.

## D-009 — Coverage is a first-class type
**Decision.** `ContentCoverage` travels from ingestion to the UI; partial coverage forces a
`limitations` entry on the card, and the digest requires `limitations` to be non-empty for partial
coverage at construction time.
**Why.** The directive forbids claiming full-article analysis when only a headline or snippet was
available.

## D-010 — Report `UNKNOWN` accelerators rather than assuming them
**Decision.** `AcceleratorAvailability.UNKNOWN` exists and never satisfies a model requirement. The
Android probe only reports `AVAILABLE` when a delegate library actually loads.
**Why.** NPU/GPU support is device- and driver-specific; the directive forbids claiming acceleration
without physical-device evidence.

## D-011 — Offline verification harness instead of skipping tests
**Decision.** `tools/jvm-verify/` compiles `core` (and Android-free app logic) with a standalone
Kotlin compiler and runs JUnit-style tests via a source-compatible shim.
**Why.** The sandbox cannot reach Maven Central. The alternative — writing code with no executed
tests — would make every status claim unverifiable.
**Consequence.** Tests reference only standard JUnit 4 symbols and `kotlinx.coroutines`, so they run
unchanged under the real Gradle build.

## D-012 — Ten destinations, five in the bottom bar
**Decision.** Memory, Models, Voice Lab, Browser, Automation and Settings live behind "More".
**Why.** At 360 dp, six or more bar items become illegible and untappable. Today/Agent/Intelligence/
Activity are the surfaces used daily.
