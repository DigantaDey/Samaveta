# SAMAVETA — TEST REPORT

**Report date:** 2026-11-20
**Build revision:** see `git log -1` at time of reading
**Verdict:** domain logic **PASS**; Android layer **NOT COMPILED**; device verification **NOT PERFORMED**

---

## 1. Executive summary

| Area | Executed? | Result |
|---|---|---|
| `core` unit tests | **Yes** — real JVM | 408/408 pass |
| App pure-logic unit tests | **Yes** — real JVM | 66/66 pass |
| Android UI compilation | No — no Android SDK in this environment | Deferred to CI |
| Lint / static analysis | No | Deferred to CI |
| Instrumentation tests | No | Not written yet |
| Physical device | No | **Nothing is VERIFIED** |

```
$ ./tools/jvm-verify/verify.sh
== compiling verification shim
== compiling core (main sources)
== compiling core tests
== compiling app pure logic (Android-free sources only)
== running tests
Tests run: 474, Passed: 474, Failed: 0, Skipped: 0
VERDICT: PASS
```

## 2. Environment

- Kotlin 2.4.21 (standalone compiler), OpenJDK 25 runtime, Linux x86-64.
- No Android SDK, no Gradle distribution, no Maven Central access (see
  `DEVELOPMENT_ENVIRONMENT.md`). Hence `tools/jvm-verify`, which compiles the pure-Kotlin modules
  and runs JUnit-style tests through a source-compatible shim.
- These are **not** substitutes for instrumentation or device tests and are not presented as such.

## 3. Coverage by requirement

### 3.1 Security (highest-risk area)

| Case | Test | Result |
|---|---|---|
| Every capability denied by default | `deniesEverythingByDefault` | PASS |
| CRITICAL cannot be persistently granted | `criticalCapabilityCannotBeRememberedPersistently` | PASS |
| Emergency stop overrides a granted capability | `emergencyStopBeatsAGrantedCapability` | PASS |
| Pausing automation blocks mutations, keeps reads | `pausingAutomationBlocksMutationsButKeepsReads` | PASS |
| State-changing actions always require confirmation | `stateChangingCapabilityAlwaysRequiresConfirmation` | PASS |
| Missing platform permission ≠ user denial | `platformPermissionGapIsDistinctFromUserDenial` | PASS |
| Scope restrictions limit targets | `scopeRestrictionLimitsRecipients` | PASS |
| Confirmation is single-use | `confirmationIsSingleUse` | PASS |
| Confirmation cannot be redirected to another target | `confirmationCannotBeRedirectedToAnotherTarget` | PASS |
| Expired confirmation rejected | `expiredConfirmationIsRejected` | PASS |
| Audit log bounded | `auditLogRecordsAndBoundsSize` | PASS |
| Audit refuses credential-like detail | `auditRecordRefusesUnredactedSecrets` | PASS |
| SUCCEEDED impossible without evidence | `actionCannotBeReportedSuccessfulWithoutConfirmedEvidence` | PASS |
| Inconclusive evidence → UNCONFIRMED | `inconclusiveEvidenceYieldsUnconfirmedNotSuccess` | PASS |
| Timeout → UNCONFIRMED (not success) | `timeoutIsReportedAsUnconfirmed` | PASS |

### 3.2 LAYA boundary

| Case | Result |
|---|---|
| Response for a different request id rejected | PASS |
| Unknown schema version rejected | PASS |
| Unknown step kind rejected (never guessed) | PASS |
| Empty plan rejected | PASS |
| Not-configured adapter fails honestly | PASS |
| Mock reported as `MOCK_TEST_ONLY` and blocks release | PASS |
| Transport failures retried, malformed responses not retried | PASS |
| No request sent past its deadline | PASS |
| Uninstalled / unverified / NPU-only model cannot execute | PASS |
| Unauthorised capability invocation rejected | PASS |
| Duplicate or out-of-order step indices rejected | PASS |
| Installation state re-checked on every validation | PASS |

### 3.3 Model integrity

| Case | Result |
|---|---|
| Installs only when SHA-256 matches | PASS |
| Deletes artifact and partial file on mismatch | PASS |
| Refuses plaintext HTTP | PASS |
| Refuses path traversal in model id | PASS |
| Network failure surfaced, not swallowed | PASS |
| Tampered installed file detected | PASS |
| Checksum comparison case-insensitive | PASS |
| Atomic overwrite of existing install | PASS |
| 3 GB device refuses a 3 GB model | PASS (admission suite) |

### 3.4 Memory / personalisation

Explicit outweighs inferred; damping bounds weights; 30-day half-life halves weight; mute is sticky
until explicit unmute; store evicts least-recently-used; feedback capped; cosine/top-k correct and
dimension-checked. All PASS. **A test caught a real defect:** a later positive signal silently
un-muted a muted topic; fixed and covered.

### 3.5 Model output and prompt injection

| Case | Result |
|---|---|
| JSON extracted from fenced/prose-wrapped output | PASS |
| Malformed JSON *inside* the object rejected (never repaired) | PASS |
| Braces and escapes inside strings handled | PASS |
| Missing field rejected, path names the field | PASS |
| Wrong type rejected (no coercion of `42` to `"42"`, no truncation of `3.7`) | PASS |
| Unknown field rejected (common injection carrier) | PASS |
| Enum/allow-list and integer bounds enforced | PASS |
| Nested list element failure names `steps[1]` | PASS |
| Error messages never echo raw model output | PASS |
| Detects instruction override / role impersonation / credential exfiltration | PASS |
| Detects capability escalation / tool-call smuggling / zero-width hidden text | PASS |
| Ordinary news content not flagged | PASS |

**Note on scope.** `InjectionDetector` is a detector, not a boundary. The boundary is the
deterministic validator + authorizer pair; the detector exists so untrusted content is quarantined
and surfaced in diagnostics.

### 3.6b Agent browser (hostile-content surface)

| Case | Result |
|---|---|
| `javascript:`, `data:`, `blob:`, `file:` schemes blocked | PASS |
| Custom schemes (`intent:`, `tel:`, `mailto:`) handed to the user, never auto-opened | PASS |
| Loopback / private IPv4 / `.local` / link-local metadata IP refused | PASS |
| Agent-initiated navigation must be https | PASS |
| Allowlist matches host-or-subdomain only (`notwikipedia.org` refused) | PASS |
| Userinfo spoofing (`trusted.com@evil.com`) refused | PASS |
| Password fields never typed into | PASS |
| Invisible or disabled elements never clicked | PASS |
| Submit / navigation / paywall / login wall requires confirmation | PASS |
| Any verification challenge suspends all interaction | PASS |
| Element refs are generation-scoped and stale after navigation | PASS |
| Reader mode strips boilerplate per sentence, keeps glued real content | PASS |
| Reader mode never returns an empty document; falls back with low confidence | PASS |
| Bounded context cuts at a sentence boundary | PASS |

### 3.6c Action pipeline and plan execution

| Case | Result |
|---|---|
| Denied capability never reaches the executor | PASS |
| Emergency stop beats a granted capability | PASS |
| Mutating action without confirmation refused before execution | PASS |
| Forged confirmation token refused | PASS |
| Confirmation replay refused (single-use) | PASS |
| Confirmation bound to one target | PASS |
| **Expired confirmation refused** — regression test for a real expiry bug | PASS |
| SUCCEEDED requires confirmed evidence | PASS |
| Silent integration reported UNCONFIRMED, never success | PASS |
| Thrown exception on a mutating action → UNCONFIRMED (not FAILED) | PASS |
| Thrown exception on a read-only action → FAILED | PASS |
| Missing executor reported instead of pretending success | PASS |
| Every outcome audited exactly once | PASS |
| Plan using an undeclared capability rejected before running | PASS |
| Plan naming a nonexistent capability rejected | PASS |
| Out-of-order / duplicate / non-zero-based step indices rejected | PASS |
| Runaway plan (> 24 steps) and oversized instruction rejected | PASS |
| Declined confirmation stops the task with nothing executed | PASS |
| User approval cannot substitute for a missing grant | PASS |
| Cancellation stops at a step boundary, remaining steps NOT_RUN | PASS |
| Failed step stops the plan; skipped steps reported NOT_RUN | PASS |

**A test caught a real defect:** `ConfirmationBroker.grant()` deleted the pending request that
`consume()` used to check expiry, so a granted confirmation never expired. Expiry now lives on the
`ConfirmationGrant` itself and is covered by `expiredConfirmationIsRefusedEvenAfterItWasGranted`.

### 3.6c2 LAYA v1 (real contract)

Reconciled against `github.com/NandhaKishorM/laya` — `docs/http-api.md`,
`sdk/typescript/src/types.ts`, `sdk/typescript/src/validation.ts`, `sdk/python/tests/_support.py`.

| Case | Result |
|---|---|
| Predict request encodes state, questions, optional controls | PASS |
| Absent optional fields omitted (never sent as `null`) | PASS |
| `min_confidence` bucket keys validated against LAYA's own grammar | PASS |
| LAYA's documented sample response decodes fully | PASS |
| `noul` answer with no confidence accepted; missing `routing` accepted | PASS |
| Absent `abstention` means *no gate ran*, never "passed" | PASS |
| Batch responses decode per result | PASS |
| `/health` decodes both open and `LAYA_API_KEY`-restricted payloads | PASS |
| Unknown answer type rejected | PASS |
| Choice label outside its own probabilities rejected | PASS |
| Out-of-range, negative and non-numeric probabilities rejected | PASS |
| Unknown abstention state rejected | PASS |
| Score without legend rejected; non-object payload rejected | PASS |
| Error bodies decoded from both `detail` and `error` shapes | PASS |
| 422 never retried; 503 retried exactly once | PASS |
| Connection failure leaves the client visibly degraded | PASS |
| Plaintext remote endpoint refused; loopback allowed | PASS |
| Truncated request refused before any other consideration | PASS |
| Abstained / unevaluated answers never acted on | PASS |
| Low-confidence intent or capability asks instead of guessing | PASS |
| Capability SAMAVETA does not have is unusable, not ignored | PASS |
| A model answering "no confirmation needed" cannot remove confirmation | PASS |
| Capability question criteria generated from the real registry | PASS |

**Two real defects found during this work.** (1) The PROPOSED planner contract was not LAYA's
interface at all — reconciling it against the repository was the only way to find this; it is now
marked SUPERSEDED. (2) A nullable `lang` value silently vanished from the encoded request because
`"key" to nullableValue` builds a `Pair<Any?, Any?>` inside the JSON builder and is then discarded.

### 3.6c3 LAYA live payloads and the model catalog

`LayaLiveContractTest` (15 tests) replays bodies captured from a **running `laya-serve`** — LAYA's own
offline fixture server, since the real weights were not downloadable here.

| Case | Result |
|---|---|
| A real `200` prediction decodes: choice, `noul`, `score` with legend | PASS |
| `usage.options` with `distinct < total` is recognised as collapsed evidence | PASS |
| A collapsed-option response is refused with the real reason, not "0 tokens dropped" | PASS |
| The batch body is `{"states":[...], "questions":{...}}` — the per-state form is rejected | PASS |
| `/health` with loaded checkpoints decodes; `device_is_preference` is not asserted true | PASS |
| A real `401` body surfaces as an auth failure, not a parse error | PASS |

**Three real defects found by running the server rather than the docs.** The batch encoder sent a shape
`laya-serve` rejects with `400`; `usage.options` was being dropped, which hid the fact that every
option had collapsed onto one span; and the refusal text for that case claimed no tokens were lost.
All three are fixed, and the captured bodies (`core/src/test/resources/laya-live/`) make them
regressions.

`ModelCatalogTest` (17 tests) covers the 27 supplied models as a gate rather than a gallery.

| Case | Result |
|---|---|
| All 27 listed once, every one `CANDIDATE`, no artifact hash, nothing downloadable | PASS |
| Constructor refuses a verified status without a real SHA-256 | PASS |
| `toDescriptor` throws for a candidate; carries licence flags once a hash exists | PASS |
| Non-commercial entries are exactly the two CC-BY-NC-SA TTS models and never recommended | PASS |
| Gated entries recorded; a pick prefers ungated when anything ungated fits | PASS |
| Abliterated/uncensored entries flagged and never the default pick | PASS |
| Estimates are labelled `ESTIMATED`, context lengths `DECLARED`, no `MEASURED` anywhere | PASS |
| Peak RAM always exceeds artifact size; the size estimate is MB not bytes | PASS |
| Best pick is deterministic and returns null rather than a model that will not fit | PASS |

### 3.6c4 The real transport and the agent turn

`LayaTransportTest` (12) and `LayaAgentTest` (13) run against a **loopback `HttpServer`**, so
`LayaUrlTransport` performs real sockets, real headers and real bodies rather than being reviewed.

| Case | Result |
|---|---|
| POST carries method, body and `Authorization: Bearer …` | PASS |
| No token configured means no header at all (not an empty one) | PASS |
| A 302 is returned, never followed | PASS |
| Plaintext to a remote host refused before connecting; loopback allowed | PASS |
| `http://localhost.evil.example` refused (suffix spoofing) | PASS |
| A 200 KB body under a 1 KB cap is discarded | PASS |
| A hanging server becomes a `Timeout` in under 2.5 s | PASS |
| A 400 keeps the server's own `detail` verbatim | PASS |
| The captured prediction travels over a socket, decodes, and is still refused | PASS |
| A 401 leaves the client `UNAUTHORISED`, not `CONNECTED` | PASS |
| An unauthenticated `/health` 200 cannot launder a refused token back to connected | PASS |
| A POST error body survives (no fixed-length streaming mode) | PASS |
| A refused connection reports `DEGRADED`, not "never reached" | PASS |
| Only a real `/health` upgrades the state to `CONNECTED` | PASS |
| Unconfigured vs unreachable produce different sentences | PASS |
| A 422 surfaces the server's precise reason | PASS |
| A malformed 200 is refused, not parsed hopefully | PASS |
| A confident answer becomes a proposal that says nothing was executed | PASS |
| A model saying "no confirmation needed" cannot remove confirmation | PASS |
| Whitespace is not sent to the engine | PASS |

### 3.6d Intelligence ingestion

| Case | Result |
|---|---|
| RSS and Atom both parsed, `href` links handled | PASS |
| CDATA unwrapped, HTML/script stripped from titles and summaries | PASS |
| Entries without a title or http(s) link rejected individually | PASS |
| `javascript:` links dropped at conversion | PASS |
| Non-feed documents reported unreadable, not as an empty feed | PASS |
| Absent or malformed dates stay `null` (never "now", never epoch 0) | PASS |
| Feed summaries never claim full-text coverage | PASS |
| Feeds capped at 200 entries and 4 MB | PASS |
| Total source failure reported as a failed refresh, not a quiet day | PASS |
| One failed source does not suppress other sources' stories | PASS |
| Unreadable document counts as a failed source | PASS |
| ETags round-trip; 304 accepted as healthy | PASS |
| 304 must not be mistaken for a redirect (**real bug found and fixed**) | PASS |
| Redirects refused with the target named | PASS |
| HTML served as 200 refused by content type | PASS |
| Feeds must be https, enforced at construction and fetch | PASS |

### 3.6 Task lifecycle and recovery (Scenario J)

| Case | Result |
|---|---|
| Running task recovered as INTERRUPTED with its in-flight step recorded | PASS |
| Verified steps preserved across recovery | PASS |
| Interrupted task cannot resume without an explicit user decision | PASS |
| UNCONFIRMED never auto-retried | PASS |
| Retry after failure with a side effect requires confirmation | PASS |
| Resume point skips already-verified steps | PASS |
| Terminal states reject further transitions | PASS |
| Side-effect flag is sticky across later read-only steps | PASS |

### 3.7 Information intelligence

Dedupe collapses the same event across outlets, and **does not** merge stories whose numeric tokens
differ (a test caught "Android 16" merging into "Android 17"); tracking parameters stripped; story
ids stable and traceable; headline-only coverage forces a stated limitation; summarizer failure
degrades to the publisher excerpt; missing publication date stays unknown; muted topics excluded with
an explanation. All PASS.

## 4. Defects found by testing (and fixed)

| # | Defect | Severity | Resolution |
|---|---|---|---|
| 1 | Positive feedback silently lifted an explicit "not interested" mute | High (personalisation honesty) | Mute made sticky; `unmute()` only via explicit user action; regression test added |
| 2 | Dedupe merged distinct stories differing only by a number | High (misinformation risk) | Numeric-signature guard + regression test |
| 3 | Story ids were 32-bit hash of a URL (collision-prone, opaque) | Medium | Ids derived from canonical URL |
| 4 | Download validation threw instead of returning `Result` failure | Medium (caller could mis-handle) | All refusals return `Result.failure`; tests aligned |
| 5 | Assertion API in tests matched neither JUnit nor the shim | Medium (tests would not compile in CI) | Shim mirrors JUnit 4's message-first signatures; tests corrected |

## 5. Not tested — explicitly

- Anything rendered by Compose (no compilation, no screenshots, no UI tests).
- Theme switching end-to-end, keyboard/insets behaviour, back navigation.
- Background execution, WorkManager, notification delivery, boot restart.
- Any inference, any model, any camera/microphone/notification capability.
- Battery, thermal, memory growth, cold-launch time.
- Accessibility (TalkBack, font scaling, RTL).
- Anything on a real device. **No benchmark or compatibility claim in this repository is
  device-verified.**

## 6. How to reproduce

```bash
# In an environment with a JVM and kotlinc:
./tools/jvm-verify/verify.sh

# In an environment with the Android SDK:
./gradlew verify          # core tests + app unit tests + lint
./gradlew :app:assembleDebug
```
