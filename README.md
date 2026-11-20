<div align="center">

# SAMAVETA

### समवेत

**Intelligence, United.**

A local-first, multi-model AI platform for Android.
First major release: **SAMAVETA 1.0 — SANTOOR**

</div>

---

## What this is

SAMAVETA is a personal intelligence layer for Android: one coherent system for conversation,
perception, memory, information intelligence and user-authorised device actions — built around
on-device inference rather than a chat window pointed at a cloud API.

The decision engine, **LAYA**, is developed and maintained independently. SAMAVETA validates and
executes; it does not contain a competing planner.

## Current state — read this first

This repository is **early**. It contains a verified domain core and a written Android app. It is
not installable yet.

| | |
|---|---|
| Domain logic (`:core`) | **163/163 unit tests pass** — executed on a real JVM |
| Android app (`:app`) | Written; **never compiled** in this environment (no Android SDK) |
| LAYA decision engine | **Not connected** — no production contract supplied. Status: `NOT_CONFIGURED` |
| Models | None integrated or downloadable |
| Voice / vision / browser / device capabilities | Not implemented |
| Physical device verification | **None — nothing is marked VERIFIED** |

Full, blunt detail: [`PROJECT_STATUS.md`](PROJECT_STATUS.md) and
[`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md). The environment constraints that make the above true
are documented in [`docs/DEVELOPMENT_ENVIRONMENT.md`](docs/DEVELOPMENT_ENVIRONMENT.md).

## Build & verify

```bash
# 1. Domain logic — works with any JDK + kotlinc, no Android SDK needed
./tools/jvm-verify/verify.sh

# 2. Full Android build (needs JDK 17 + Android SDK 35)
./gradlew verify                # core tests, app unit tests, lint
./gradlew :app:assembleDebug    # produces a debug APK
```

CI (`.github/workflows/verify.yml`) runs the domain tests, the Android build/lint/unit tests, and a
set of **release guards**: no committed keystores, no model weights in git, no Android imports in
`core`, and no mock LAYA reachable from `app/src/main`.

## Architecture at a glance

```
:core   pure Kotlin — no android.* imports (enforced by CI)
        device · model registry & admission · laya contract/adapter/validator
        memory & personalisation · information pipeline · security & verification

:app    Android — Compose UI, Hilt, DataStore, model downloads
```

Request path (once LAYA is connected):

```
input → LayaAdapter → LAYA → strict decode → LayaDecisionValidator → CapabilityAuthorizer
      → confirmation → execution → ActionVerifier (evidence required) → audit → Activity
```

Each stage is deterministic and independently tested. A model cannot execute on this device unless
the registry admits it; an action cannot be reported successful without confirmed evidence.

## Design

Warm off-white / near-black charcoal surfaces with a single muted copper accent; one type scale;
small radii; hairlines instead of card grids. System theme by default. See
[`docs/DESIGN_SYSTEM.md`](docs/DESIGN_SYSTEM.md).

## Documentation

| Document | Purpose |
|---|---|
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | Honest current state, test evidence, blockers |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Modules, request path, boundaries |
| [`FEATURE_MATRIX.md`](FEATURE_MATRIX.md) | Every requirement with status and location |
| [`LAYA_INTEGRATION.md`](LAYA_INTEGRATION.md) | Contract, adapter, reconciliation checklist |
| [`MODEL_CATALOG.md`](MODEL_CATALOG.md) / [`MODEL_LICENSES.md`](MODEL_LICENSES.md) | Model candidates and licensing registry |
| [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) | What does not work, plainly |
| [`DECISIONS.md`](DECISIONS.md) | Why things are the way they are |
| [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) | Executed tests and their results |
| [`docs/PRIVACY.md`](docs/PRIVACY.md) | Every network path, every protection |
| [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) | Directive → implementation traceability |

## Principles the code enforces

- **Deny by default.** No capability runs without a grant; critical capabilities can never be
  persistently granted.
- **No false success.** `SUCCEEDED` is impossible without confirmed evidence; timeouts are
  `UNCONFIRMED`.
- **No false attribution.** A story summarised from a headline says so; a missing date stays unknown.
- **No unverified hardware claims.** GPU/NPU are `UNKNOWN` until a probe proves otherwise.
- **No silent cloud.** There is no remote inference path at all.
