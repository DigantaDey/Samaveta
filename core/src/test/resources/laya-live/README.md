# Captured LAYA responses

These files are **verbatim responses** captured from real `laya-serve` processes, not hand-written
fixtures. `LayaLiveContractTest` asserts against them, so the decoder stays honest about what the
server actually sends.

## Provenance

| | |
|---|---|
| Date | 2026-11-20 |
| Engine | `laya` from PyPI, `pip install "laya[serve]"`, in a venv |
| Server A | `LAYA_PRELOAD=0 python -m laya.serve` on `127.0.0.1` — no checkpoints available |
| Server B | LAYA's own `tests/sdk_server_fixture.py` — a tiny **offline** checkpoint built in-process, no Hugging Face download, all three checkpoint names attached to it |
| Device | CPU only |
| Auth | Server B sets `LAYA_API_KEY=integration-test`; fixtures were captured with that bearer |

Server B is why a real `200` exists here at all. It is LAYA's own test harness, used for exactly this
purpose upstream ("Serve a tiny offline checkpoint for SDK integration tests"). **It is a transport and
schema fixture, not a quality benchmark**: its weights are randomly initialised, so its answers are
near-uniform and it collapses every question's options.

## What each file proves

| File | Captured from | What it pins |
|---|---|---|
| `health.json` | B | A populated health payload: three checkpoints loaded, `device_is_preference: false`, per-checkpoint `cpu_fallbacks` with `last_reason: null` |
| `route.json` | A | `/v1/route` with `explain: true`; `reason` carries model text |
| `prediction.json` | B | **A real successful prediction**: choice/score/noul answers, `abstention` + `abstention_threshold`, `x_jev_confidence`, and the `usage.options` block the documentation omits |
| `batch.json` | B | `/v1/systemone/batch` with `results[]`, one question set over two states |
| `error-400-missing-questions.json` | B | 400 shape for a body without `questions` |
| `error-401-no-key.json` | B | 401 `{"detail":"invalid or missing bearer token"}` when the key is required |
| `error-422-unknown-model.json` | B | 422 naming the model **and** the valid alternatives, as a plain `detail` string |
| `error-422-max-len.json` | B | The 8192 token ceiling, observed server-side |
| `error-500-inference-failed.json` | A | 500 `{"detail":"inference failed"}` for a deployment with no checkpoint — the reason 5xx is **not** retried |

## Two things this live run changed in the implementation

1. **`encodeBatch` was wrong.** LAYA expects `{"states": [...], "questions": {...}}` with `questions`
   at the **top level**, and answers `400 request body must be an object with 'states' and 'questions'
   fields` otherwise. The per-state `{state, questions}` form belongs to the in-process
   `Router.predict_batch`, not the HTTP contract. The first version of this code sent the wrong shape;
   it was only found by running it.
2. **`usage.options` is real and matters.** Every question reports `total`/`distinct`/
   `tokens_per_option`. When `distinct < total` the options were collapsed into one span and the model
   cannot tell them apart. That is lost evidence, so it now counts toward
   `LayaUsage.lostEvidence` and refuses the action with a message naming the collapse — the previous
   message said "0 token(s) dropped", which was nonsense.

## What these files do NOT prove

- **No quality benchmark.** Server B's checkpoint is randomly initialised; accuracy, calibration and
  abstention rates measured on it would be meaningless. `MODEL_BENCHMARKS.md` stays empty.
- **No real checkpoint was ever loaded.** Downloading LAYA's actual weights from Hugging Face is
  blocked in this environment, so `english`/`multilingual`/`typed-decisions` have not produced a real
  answer here.
- **No Android device involved.** Nothing here has run on a phone.
