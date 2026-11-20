# LAYA INTEGRATION

**Status: BLOCKED for production. Nothing in this build connects to a real LAYA engine.**

## 1. What is contractually true

LAYA is an independently developed and maintained decision engine owned by a different developer.
SAMAVETA must not:

- reimplement LAYA's decision logic internally,
- invent SDK classes, method names or API shapes,
- silently substitute a local planner that behaves like LAYA,
- report the integration as operational before it is.

As of this commit, **no production interface has been supplied**. Therefore:

| Item | State |
|---|---|
| Production transport | absent (`LayaConfig.NOT_CONFIGURED`, `transport = null`) |
| Integration status at runtime | `NOT_CONFIGURED` — shown in Today, Agent, Settings and diagnostics |
| Mock | `MockLayaTransport`, test-only, cannot be referenced from `app/src/main` (CI check) |
| Decision logic inside SAMAVETA | none |

## 2. What SAMAVETA owns (and has implemented)

`core/src/main/kotlin/com/samaveta/android/core/laya/`

- **`LayaTransport`** — SAMAVETA-owned transport interface (`exchange(requestJson, timeoutMs)`).
  Production kinds anticipated: `HTTP_JSON`, `LOCAL_IPC`, `SDK_LIBRARY`.
- **`LayaAdapter`** — owns everything around the external engine: per-call timeout, deadline
  enforcement (a request is never sent past its deadline), bounded retry **only** for transport
  errors, cancellation, and error taxonomy.
- **`LayaFailure`** — `NotConfigured`, `Unavailable`, `Timeout`, `Transport`, `MalformedResponse`,
  `UnsupportedVersion`, `Rejected`. Every case surfaces in diagnostics and to the user.
- **`LayaDecisionValidator`** — deterministically re-checks every proposed model and capability
  against the registry, device, and authorized capability set *before* execution.
- **`LayaRequestMapper`** — publishes device signals and the model inventory (with, per model, why it
  is unavailable) so LAYA can decide using SAMAVETA's real constraints.

## 3. The PROPOSED wire contract

`LayaWireContract` defines a schema marked **PROPOSED**, version
`2026-11-proposed-1`. It exists so the mapping code, validation and tests can be written now and
reconciled later. **It is not LAYA's contract.**

Request (`type: decision_request`) carries:

- `schema_version`, `request_id`, `session_id`, `utterance`, `locale`, `deadline_epoch_ms`
- `context`: `recent_turns` (≤ 12, each ≤ 8000 chars), `active_task_id`, `user_confirmed_intents`
- `device`: memory tier, RAM, storage, thermal level, battery, verified accelerators, API level,
  ABIs, `offline`
- `models[]`: id, installed, available, capabilities, input modalities, peak RAM, storage,
  accelerators, context window, `unavailable_reason`
- `capabilities[]`: the capability ids the user has actually granted

Response (`type: plan | clarification | no_plan`) must echo `request_id`; a mismatch, an unknown
`schema_version` or an unknown step `kind` is rejected outright (`MalformedResponse` /
`UnsupportedVersion`) and never guessed at.

## 4. Reconciliation checklist (required before production)

When LAYA's developer supplies the real interface, complete every item and record it in
`DECISIONS.md`:

- [ ] Obtain the official contract: version id, transport, endpoint/auth model, rate limits.
- [ ] Replace `LAYA_WIRE_SCHEMA_VERSION` with LAYA's real version string.
- [ ] Reconcile every field in `LayaRequestMapper` / `LayaWireContract` — names, types, requiredness.
- [ ] Map LAYA's error model onto `LayaFailure` (do not swallow classes SAMAVETA cannot interpret).
- [ ] Implement one `LayaTransport` for the real transport kind; keep `MockLayaTransport` for tests.
- [ ] Change `AppModule.provideLayaAdapter()` to construct the configured adapter.
- [ ] Confirm the ordering of decision vs. validation against LAYA's documented flow.
- [ ] Run integration tests against a real LAYA instance; record pass/fail evidence.
- [ ] Move `LayaIntegrationState` to `VERIFIED` only with that evidence.
- [ ] Update `PROJECT_STATUS.md`, `FEATURE_MATRIX.md`, `KNOWN_LIMITATIONS.md`.

## 5. Degradation rules (implemented)

When LAYA is not operational:

- The app remains fully usable for independent functions (browsing, memory, settings, model management).
- Anything requiring planning says so plainly — the Agent screen replies that no plan was produced
  and that **nothing was executed**. It does not fabricate a plan or silently run a rule-based
  substitute.
- The integration state is always visible (`NOT_CONFIGURED`, `MOCK_TEST_ONLY`, `DEGRADED`, …), and
  `LayaIntegrationGuard.releaseBlockers()` returns a non-empty list so a release cannot ship claiming
  a working integration.

## 6. Privacy of the request payload

The payload is limited to: the current utterance, up to 12 recent turns, an optional active task id,
explicitly confirmed intents, device conditions, model availability and granted capability ids.
It never contains: message bodies, contact details, file contents, screenshots, audio, or memory
records. If SAMAVETA adds a field, it must be justified here and in `docs/PRIVACY.md`.
