# FastConformer Spanish sidecar smoke — 2026-10-04

This is an exploratory same-input runtime/punctuation screen, not an accuracy
evaluation or an end-to-end VozLocal dictation comparison. It ran only in the
separate validation APKs; production model selection and routing were unchanged.
The existing repository `test_audio_2min.ogg` was used locally because its
redistribution permission is undocumented. Only hashes, timings, build/model
provenance, and punctuation counts are retained here; no audio or transcript is
included.

Three UUID-correlated FastConformer instrumentation runs and two Moonshine Small
control runs used the same 10-second, mono 16 kHz float32 PCM identity. The
observed order was FastConformer, Moonshine, Moonshine, FastConformer,
FastConformer. All five retained tests reported success. One preliminary
FastConformer run before host-side UUID freshness checking is excluded from the
aggregate. Candidate-level warm medians were
276.186 ms (FastConformer, n=9, range 268.058–285.024 ms) and 1,857 ms
(Moonshine, n=6, range 1,723–2,191 ms). These are descriptive fixture timings,
not a general speedup claim: FastConformer used two threads while Moonshine used
its SDK defaults, temperatures differed across runs, and the sample is small.
The FastConformer initialization span includes bundle SHA verification plus
native initialization; Moonshine's `cold_load_ms` starts at `loadFromFiles` after
manifest verification. Neither is a physical-cold or user stop-to-text measure.

On this one unknown-reference clip, FastConformer emitted 2 commas and 1 `?`,
but no period or inverted `¿`. Moonshine emitted no commas, questions, or
inverted questions; its first three outputs had 4, 1, and 1 periods, respectively.
That describes formatting only. Without a verified reference, it does not show
which output is more accurate or that punctuation is correct. Do not promote
either model based on this fixture.

The complete sanitized per-run measurements and hashes are in
[`screening.json`](screening.json). See [issue #20](https://github.com/lander16/voz-local/issues/20)
for the remaining device-lifecycle, long-clip, and accuracy acceptance work.
