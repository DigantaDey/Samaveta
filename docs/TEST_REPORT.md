# SAMAVETA — TEST REPORT

**Report date:** 2026-11-20
**Build revision:** see `git log -1` at time of reading
**Verdict:** domain logic **PASS**; Android layer **NOT COMPILED**; device verification **NOT PERFORMED**

---

## 1. Executive summary

| Area | Executed? | Result |
|---|---|---|
| `core` unit tests | **Yes** — real JVM | 80/80 pass |
| App pure-logic unit tests | **Yes** — real JVM | 40/40 pass |
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
Tests run: 120, Passed: 120, Failed: 0, Skipped: 0
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

### 3.5 Information intelligence

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
