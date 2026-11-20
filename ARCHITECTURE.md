# SAMAVETA — ARCHITECTURE

## 1. Principles

1. **Local-first.** Inference, memory and personalisation happen on the device. Network use is
   limited to what the user explicitly starts (model download) or configures (information sources).
2. **The decision engine is external.** LAYA decides; SAMAVETA validates and executes. SAMAVETA
   does not contain a competing decision engine, and nothing in `core` attempts to plan tasks.
3. **Deterministic boundaries around a probabilistic core.** Permissions, capability limits,
   verification and integrity checks are enforced in code, never delegated to a model. A prompt
   injection that convinces a model to "send the message" still hits `CapabilityAuthorizer`.
4. **Never claim unverified success.** Types make false success unrepresentable
   (`ActionReport` requires `Confirmed` evidence for `SUCCEEDED`).
5. **Testable by construction.** Logic that must be trusted lives in pure Kotlin with no Android
   imports, so it can be executed and tested without a device.

## 2. Module layout

```
:core                      pure Kotlin, no android.* / androidx.* imports
├── device/                DeviceProfile, memory tiers, thermal/battery policy, accelerators
├── json/                  dependency-free JSON model, parser, writer
├── laya/                  integration contract, adapter, decision validator
├── model/                 ModelDescriptor, ModelRegistry, admission decisions
├── memory/                memory items, feedback → interests, relevance ranking, vector math
├── intelligence/          ingestion types, dedupe, entity extraction, dates, digest builder
└── security/              capabilities, authorization, confirmation, audit, action verification

:app                       Android application
├── app/                   Application, MainActivity, theme VM, startup diagnostics
├── di/                    Hilt modules
├── data/                  DataStore preferences
├── models/                device profile provider, runtime probe, model downloader
└── ui/
    ├── design/            palette, typography, spacing, shared components
    └── screens/           Today, Agent, Activity, Intelligence, Memory, Models, Voice,
                           Browser, Automation, Settings
```

`core` is a **hard boundary**: CI fails if any file under `core/src/main` imports `android` or
`androidx`. This is what keeps the security-critical logic runnable outside an emulator.

## 3. Request path

```
user input
   ↓
Agent (UI) ──► LayaAdapter ──► [ LAYA ]              ← external, not implemented here
                    ↓
              LayaWireContract.decodeResponse        (strict; rejects unknown versions/steps)
                    ↓
             LayaDecisionValidator                   ← deterministic; may reject the plan
                    ↓
             CapabilityAuthorizer                    ← deterministic; may deny
                    ↓
             ConfirmationBroker                      ← single-use, target-bound approval
                    ↓
             [ capability / model execution ]        ← NOT IMPLEMENTED YET
                    ↓
             ActionVerifier → ActionReport           ← SUCCEEDED requires evidence
                    ↓
                 AuditLog → Activity screen
```

The two validation stages are deliberately independent: a plan can be well-formed for LAYA and
still be unexecutable here (model missing, accelerator unverified, insufficient RAM). When that
happens `PlanVerdict.Rejected.toFeedbackJson()` is returned to LAYA instead of silently substituting
a different model.

## 4. Model admission

`ModelRegistry.evaluate()` is the single gate between "a model is mentioned" and "it may run".
It checks, in order: registration, installation, integrity (SHA-256 verified flag), accelerator
requirement (an `UNKNOWN` accelerator never counts as available), ABI, RAM (peak, not average),
storage, and device class. Results are `Admitted(accelerators)` or `Rejected(reason, explanation)`
with a user-facing explanation.

`AdmissionRejection.retryableWithoutUserAction` distinguishes "downloading, try later" from
"this device can never run it".

## 5. Device health

`DeviceHealthPolicy.permits(priority, thermal, memoryPressure, battery)` refuses work rather than
degrading the whole app. Background and maintenance work yields first; the interactive request a
user is waiting on is refused last. Thermal blocking is progressive, and `SHUTDOWN` blocks
everything.

## 6. Information pipeline

```
RawItem (with ContentCoverage)
   ↓ Deduplicator            canonical URL + title Jaccard + numeric signature guard
   ↓ EntityExtractor         lexicon (0.9) vs capitalisation heuristic (0.45), always labelled
   ↓ RelevanceRanker         interest × recency × credibility × novelty, explainable breakdown
   ↓ DigestBuilder           provenance mandatory; coverage limitations mandatory
IntelligenceCard
```

`ContentCoverage` (`FULL_TEXT` / `SUMMARY_ONLY` / `HEADLINE_ONLY`) is carried from ingestion through
to the UI so the product can never claim to have analysed an article it did not fetch. Missing
publication dates are reported as unknown rather than replaced with fetch time.

## 7. Memory and personalisation

Three memory kinds with mandatory provenance (`ExplicitUserStatement`, `InferredBehaviour`,
`FeedbackDerived`, `UserEdited`). Interest weights use: explicit feedback ×3, `1/√n` damping so one
tap cannot dominate, 30-day half-life decay, and sticky mutes that only an explicit user action can
lift. All of it is inspectable and deletable per item.

## 8. Persistence (planned, not implemented)

Room will hold: memories, feedback events, audit records, installed-model metadata, digest cache.
DataStore holds non-sensitive preferences only. Sensitive columns will use Keystore-backed
encryption; schema migrations are generated into `app/schemas` and must be migration-tested.

## 9. What is intentionally absent

- No telemetry, analytics or crash-reporter SDK.
- No remote inference fallback of any kind. If a local model is missing, the product says so.
- No AccessibilityService-based UI automation.
- No model weights in the repository (enforced by CI).
