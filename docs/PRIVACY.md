# SAMAVETA — PRIVACY & SECURITY POSTURE

## 1. Model of trust

Everything SAMAVETA can observe is personal: messages, screenshots, contacts, calendar, voice, and
what the user reads. The product is therefore **local-first by default, and explicit about every
exception**.

## 2. Network access — the complete list

| When | Destination | Data sent | Why |
|---|---|---|---|
| User starts a model download | The registry's HTTPS URL | Nothing but the request (no identifiers) | Fetch model weights |
| User adds an information source | That source's public URL/RSS | Nothing but the request | Retrieve public content |
| LAYA, *if configured* | The endpoint LAYA's developer specifies | Utterance, ≤12 recent turns, active task id, confirmed intents, device conditions, model availability, granted capability ids | Obtain a decision |

Nothing else leaves the device. There is **no** analytics, crash reporting, A/B service, ad SDK or
remote-inference fallback in the dependency set. If cloud features are ever added, they must identify
data, destination, provider and purpose, and require explicit consent — and there must be no silent
fallback from local to cloud.

## 3. What is never uploaded

Message bodies, notification content, contact details, screenshots, photos, documents, audio
recordings, voice embeddings, memory records, audit logs, and the contents of the user's information
digest beyond the request needed to fetch a public page.

## 4. On-device protections

- **Model integrity:** downloads are HTTPS-only; the artifact must match a published SHA-256 before
  it is promoted from `.part` to installed. A mismatch deletes the bytes.
- **Capability authorisation** is enforced in code (`CapabilityAuthorizer`), deny-by-default, with
  emergency stop winning over any grant.
- **Confirmations** are single-use, bound to one exact capability + target, and expire.
- **Audit records** refuse detail strings that look like credentials or private keys.
- **No false success:** `ActionReport` cannot be constructed as `SUCCEEDED` without confirmed
  evidence; timeouts are reported as unconfirmed.
- **Backup:** cloud backup and device-to-device transfer are disabled for all app data
  (`res/xml/data_extraction_rules.xml`), so memories and voiceprints cannot leave via Google backup.
- **Logging:** no user content is logged. Diagnostics carry states and counts only.

## 5. Permissions

Requested at install time: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`(+`_DATA_SYNC`),
`WAKE_LOCK`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `READ_THERMAL_STATE`.
Requested at runtime, only when the feature is enabled: `RECORD_AUDIO`.
Everything else (contacts, calendar, notifications access, camera, location, call roles) is
requested per-capability, only after the user enables that capability, and is revocable.

**Deliberately not requested:** AccessibilityService, `QUERY_ALL_PACKAGES`,
`MANAGE_EXTERNAL_STORAGE`/`READ_EXTERNAL_STORAGE`, AD_ID.

## 6. Sensitive data handling

- Speaker embeddings are matched locally and never used as sole authentication for sensitive actions.
- Voice profiles require consent, keep provenance, and are deletable.
- Memory items carry provenance and are individually deletable; export and full reset are supported.
- Sensitive attributes are not inferred silently; interests come from explicit feedback or clearly
  labelled inference.

## 7. Incident-relevant guarantees (tested)

See `docs/TEST_REPORT.md` §3.1. The important ones: no capability runs without a grant; no action is
reported successful without evidence; a poisoned model cannot be installed; a confirmation cannot be
replayed or redirected.
