# LAYA INTEGRATION

**Status: contract RECONCILED against LAYA's published repository, and the transport/codec verified against a REAL `laya-serve` process (health, routing and every rejection shape). A successful prediction has NOT been captured, because model weights could not be downloaded in this environment — so the integration must be shown as connected only after `/health` says so, and must never be described as end-to-end verified.**

Source of truth for everything below: [`github.com/NandhaKishorM/laya`](https://github.com/NandhaKishorM/laya) —
`docs/http-api.md`, `sdk/typescript/src/types.ts`, `sdk/typescript/src/validation.ts`,
`sdk/python/tests/_support.py`, `laya-java/README.md`. Nothing here is invented.

---

## 1. What LAYA actually is

LAYA is a **non-autoregressive, typed decision engine** (Vega/vegaml lineage): a bidirectional encoder
plus a typed head that answers `choice` / `score` / `noul` questions about one state in **one forward
pass**, with calibrated probabilities and a router that picks a checkpoint per request. It is not a
chat model and not a planner.

| Fact | Consequence for SAMAVETA |
|---|---|
| Answers are typed: `choice`, `score`, `noul` | SAMAVETA asks a fixed question set and maps answers to capabilities |
| `confidence` means different things per type | never threshold it directly; see §4 |
| It abstains, and reports conformal sets / OOD flags in the new engine | abstention is honoured, never overridden |
| It never emits plans or tool calls | **SAMAVETA plans and executes; LAYA only decides** |
| State is truncated by a token budget, visibly | truncation is a refusal condition, not a footnote |
| Deployment is `laya-serve` over HTTP (`/v1/systemone`) | transport is HTTPS (or loopback), no embedded model |

### 1.1 The correction that mattered

Before this repository was available, SAMAVETA carried a **PROPOSED** contract
(`LayaPlan`, `LayaPlanStep`, `StepKind`, `capability_invocation` steps) written from the product
directive's assumption that LAYA would return multi-step plans. That assumption is **wrong**: LAYA
returns typed answers about a state, and has no concept of a plan step, a capability, or a tool call.

Keeping the proposed contract as "the LAYA interface" would have been exactly the kind of
invented SDK the directive forbids. Instead:

- `core/.../laya/v1/` now implements the **real** contract and is what the app should call.
- `core/.../laya/` (the proposed planner contract) is retained **only** as the target of a
  documentation-marked, test-only path. It is not wired to any server, and no UI reads it.
- Planning lives entirely in SAMAVETA (`orchestration/PlanGate`, `PlanRunner`).

## 2. Implemented in SAMAVETA

`core/src/main/kotlin/com/samaveta/android/core/laya/v1/`

| File | Responsibility |
|---|---|
| `LayaV1Contract.kt` | Request/answer types, `MinConfidence` buckets, `LayaUsage`, `LayaRouting`, the `LayaV1Failure` taxonomy |
| `LayaV1Codec.kt` | Request encoding; **strict** response decoding (see §5) |
| `LayaV1Client.kt` | `/health`, `/v1/route`, `/v1/systemone`, `/v1/systemone/batch`; timeout, bounded retry, admission back-off, connection state |
| `LayaQuestions.kt` | The four questions SAMAVETA asks, and the deterministic policy that reads the answers |

Tested by `LayaV1Test` (44 cases) using LAYA's own documented payload shapes.

## 3. What SAMAVETA sends

`POST /v1/systemone` with a `state` and four questions. Stable question ids: `intent`, `capability`,
`needs_confirmation`, `sensitivity`.

```json
{
  "state": { "utterance": "Send a reply to Asha", "locale": "en-IN", "lang": "en" },
  "questions": {
    "intent":   { "type": "choice", "instructions": "...", "criteria": ["command", "question", "conversation"] },
    "capability": { "type": "choice", "instructions": "...", "criteria": ["<every Capability id>"] },
    "needs_confirmation": { "type": "noul", "instructions": "..." },
    "sensitivity": { "type": "score", "instructions": "...",
                     "criteria": ["trivial", "personal", "consequential", "irreversible"] }
  }
}
```

- `model` is **omitted** by default so LAYA's router chooses. Pinning is available but not the
  SAMAVETA default.
- The `capability` criteria are generated from `Capability.ids()`, so every answer maps back to a real
  capability and an answer naming anything else is refused as unusable.
- Only the utterance, locale and at most 6 recent turns are sent. There is no transcript upload, no
  device fingerprint, and no model inventory (LAYA serves its own checkpoints; SAMAVETA's local models
  are its own concern).
- **Network transparency:** the exact payload is inspectable before it is sent.

## 4. Reading the answers — where the safety lives

`LayaQuestions.read()` produces exactly one of: `Proposal`, `Unsure`, `NotACommand`, `Unusable`.
Only `Proposal` can reach an executor, and even then it goes through `ActionPipeline`.

Order of checks, which is the safety order:

1. **Truncation** — if `usage.truncated` or `state_tokens_dropped > 0`, refuse. Every answer was
   computed on less evidence than SAMAVETA sent.
2. **Abstention** — `abstained` or `unevaluated` on any answer ⇒ `Unsure`. `unevaluated` is *not* a
   pass; LAYA is explicit that reporting it as one would be "the same lie as reporting it as a flag".
3. **Intent floor** (`0.50` by default) — below it, ask rather than guess.
4. **Capability floor** (`0.55` by default) — below it, the request is understood but not the action.
5. **Capability mapping** — an id SAMAVETA does not have is `Unusable`, never silently dropped.

### The three confidence numbers are not interchangeable

LAYA documents this and SAMAVETA respects it:

| Field | Meaning | Used for |
|---|---|---|
| `answer_confidence` | `max(p)`; same on all three answer types | reported, and the quantity LAYA's gate thresholds on |
| `confidence` | normalised entropy (choice/score), `max(p_yes,p_no)` on noul | **never** thresholded across types |
| `x_jev_confidence` | Jev-compatibility number, absent in strict mode | reported only; never gated on |

SAMAVETA applies its own floors to probabilities and **never** treats a probability as a verdict.

### A model cannot grant itself permission

`requiresConfirmation` is `capability.mutatesState || noulYes >= 0.40 || sensitivity >= 2.0`.
LAYA answering "no confirmation needed" (p = 0.02) does not remove confirmation for a state-changing
capability, because `CapabilityAuthorizer` forces it regardless. Tested both ways.

## 5. Strict decoding

`LayaV1Codec` rejects an entire response — never partially trusts it — when it finds:

- an unknown answer `type`, or no `type`;
- a `choice` label absent from its own `probabilities`;
- any probability outside `[0,1]`, non-numeric, or non-finite (NaN/Infinity poison comparisons);
- an unknown `abstention` value;
- a `score` answer without a `legend`, or a non-numeric `score`;
- an empty `answers` object, a non-object payload, or malformed JSON.

This mirrors LAYA's own client (`sdk/typescript/src/response.ts` validates the same invariants) and is
stricter in one respect that matters for SAMAVETA: a rejected payload becomes
`LayaV1Failure.Malformed` *before* any answer is used to choose an action.

## 6. Transport and failure handling

| LAYA behaviour | SAMAVETA handling |
|---|---|
| `LAYA_API_KEY` set, unauthenticated `/health` | Only `{"status":"ok"}` decodes; `loaded` is empty and is not treated as an error |
| `503` from the admission limiter | `LayaV1Failure.Overloaded`, retried **once** |
| `5xx` | `ServerError`, **not** retried (see the note in §6.1) |
| `422` with `{"detail": "..."}` (a plain string — observed live) or `{"error": {...}}` | `Rejected(status, code, detail)` — the field that was wrong is shown; **never retried** |
| `400` malformed body | `Rejected` with the server's message |
| `500 {"detail":"inference failed"}` | `ServerError`. **Not retried**: observed for a missing checkpoint, so it is deterministic |
| Connection refused / DNS | `Unreachable`; client state becomes `DEGRADED` |
| Timeout | `Timeout`; **never retried** — the server may have run the inference |
| Idle unload (`loaded: []`) | Normal; only affects the diagnostics display |

### 6.1 What the live run changed

`laya-serve` was run locally and probed. Three findings changed the implementation:

1. **`422` carries `detail` as a plain string**, not an object. The decoder handles both, but the
   string form is the real one, and it names the offending model and the valid alternatives.
2. **`500` is deterministic.** A missing checkpoint, a broken graph or a missing runtime produces
   `{"detail":"inference failed"}` on every attempt. Retrying it — which the first implementation did
   — multiplies latency and log noise on a deployment that cannot work. Only `503` (the admission
   limiter) and dropped connections are retried now.
3. **The 8192-token ceiling is real and server-enforced**, matching `LayaPredictRequest.MAX_TOKEN_BUDGET`.

Captured payloads live in `core/src/test/resources/laya-live/` with a provenance README and are
asserted by `LayaLiveContractTest`.

Endpoint policy: **https required**, with an explicit loopback exception (`127.0.0.1`, `localhost`,
`[::1]`) for an on-device `laya-serve` in development. Plaintext to a remote host is refused at
construction, because a rewritten response is an instruction injection.

Connection state is `NOT_CONFIGURED` → `NEVER_REACHED` → `CONNECTED` (only after a successful
`/health` or call) and `DEGRADED` after a failure. Only `CONNECTED` may be described as connected.

## 7. Deployment options (all real, none embedded)

| Option | Requires | Notes |
|---|---|---|
| Remote `laya-serve` over HTTPS | A server the user or operator runs | Default assumption; needs a network connection, so **not** local-first |
| Loopback server on the same device | Termux or a companion process | Only realistic route to genuinely offline LAYA on Android. **This is what was actually exercised**: `LAYA_PRELOAD=0 python -m laya.serve` on `127.0.0.1:8321` |
| `laya-java` (ONNX Runtime) in-process | `laya.onnx` + checkpoint on device | Upstream says **"Not implemented yet: … Android."** Blocked until upstream supports it; `onnxruntime-android` exists, but shipping an unsupported path would be a claim we cannot back |

LAYA is an **external dependency**, so "offline operation" for the agent narrative cannot be
satisfied by LAYA on a phone today. That is recorded in `KNOWN_LIMITATIONS.md` rather than papered
over. SAMAVETA's own local behaviour (models, memory, browser policy, persistence) remains offline.

## 8. What is still missing

- [x] Run against a real `laya-serve`: health, routing and all rejection shapes captured.
- [ ] Capture a **successful prediction** (200). Blocked here: Hugging Face is unreachable, so no
      checkpoint could be loaded and every `/v1/systemone` returned 500. This is the single remaining
      verification gap, and it is the one that would prove the answer decoder end to end.
- [ ] Wire `AppModule` to construct a configured `LayaV1Client` from user settings, with the key in
      the Android Keystore (never in prefs, never in the APK).
- [ ] Replace the placeholder `send` composition in `AgentScreen` with `LayaQuestions.read()` output.
- [ ] Calibrate `Policy` floors on real utterances. The shipped defaults are conservative guesses,
      not measurements — they must be labelled as such in diagnostics until calibrated.
- [ ] Decide the `laya-java` question with upstream (the blocker is on their side).

## 9. Rules SAMAVETA will not break

1. No LAYA decision logic is reimplemented inside SAMAVETA.
2. No mock or scripted LAYA response ships in `app/src/main` (enforced by
   `tools/check/repo-hygiene.py`).
3. No capability is executed because a model said so; `ActionPipeline` +
   `CapabilityAuthorizer` alone authorise, and `ActionVerifier` alone reports success.
4. The integration is never described as connected until `/health` says so.
