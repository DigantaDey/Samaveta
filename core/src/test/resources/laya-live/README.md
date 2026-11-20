# Captured LAYA responses

These files are **verbatim responses** captured from a real `laya-serve` process, not hand-written
fixtures. They are used by `LayaLiveContractTest` to keep the decoder honest about what the server
actually sends.

## Provenance

| | |
|---|---|
| Date | 2026-11-20 |
| Engine | `laya` from PyPI (`pip install "laya[serve]"`), version as resolved that day |
| Server | `LAYA_PRELOAD=0 python -m laya.serve`, bound to `127.0.0.1:8321` |
| Why preload off | Model weights could not be downloaded in this environment (Hugging Face unreachable), so no checkpoint was resident |
| Device | CPU only |

## What each file proves

| File | Contents | What it pins |
|---|---|---|
| `health.json` | `{"status":"ok","loaded":[],"device":"cpu","device_is_preference":true,...}` | The health decoder matches the real payload, including an empty `loaded` list and a preference (not measurement) device |
| `route.json` | A real `/v1/route` answer with `explain: true` | Route decoding, and that `reason` carries model text |
| `error-422-unknown-model.json` | `{"detail": "unknown model '/nonexistent/ckpt'; choose one of [...]"}` | The real 422 shape: a plain `detail` **string**, not an object |
| `error-422-max-len.json` | `{"detail": "max_len exceeds server limit (999999 > 8192)"}` | The documented 8192 token ceiling, observed rather than assumed |
| `error-400-missing-questions.json` | `{"detail": "request body must be an object with a 'questions' field"}` | The 400 shape for a malformed body |
| `error-500-inference-failed.json` | `{"detail": "inference failed"}` | **Critical:** 500 means inference failed, not "busy". A missing checkpoint produces it, so it is deterministic and must never be retried |

## What these files do NOT prove

**No successful prediction has been captured.** Every `/v1/systemone` call in this environment failed
with `500 {"detail":"inference failed"}` because the checkpoint could not be downloaded. Therefore:

- the decoder's success path is verified against LAYA's *documented* payloads and LAYA's own SDK
  fixtures, not against a live answer;
- `answer_confidence`, `abstention`, `truncated` and `routing.detection` have not been observed from a
  running server.

Anything claiming the LAYA integration is "connected" or "verified end to end" is false until a
`200` prediction is captured here.
