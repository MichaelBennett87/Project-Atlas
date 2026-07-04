# OpenAI Cost-Control / Tiered AI Patch

## What changed

This patch keeps the autonomous OpenAI architecture, but stops the system from trying to send huge raw nightly datasets directly to OpenAI.

### Added
- `NightlyOpenA[OPENAI_COST_CONTROL_TIERED_AI_PATCH_NOTES.md](OPENAI_COST_CONTROL_TIERED_AI_PATCH_NOTES.md)iContextReducer`
  - Clusters and summarizes trade outcomes, dynamic entry/exit decisions, OpenAI governor journals, and current policies.
  - Writes a compact memo to `logs/openai_nightly_reduced_context.txt`.
  - Default nightly OpenAI input cap: `OPENAI_NIGHTLY_MAX_INPUT_CHARS=32000`.
  - Default examples per cluster: `OPENAI_NIGHTLY_EXAMPLES_PER_BUCKET=8`.

### Changed
- `OpenAiNightlyEntryExitPolicyReview`
  - Uses the reduced context by default instead of raw hundreds/thousands of log rows.
  - Default output cap reduced from 4000 to 1200 tokens.
  - Still supports raw mode with `OPENAI_NIGHTLY_REDUCE_CONTEXT_ENABLED=false`.

### Added quota-aware fallback
- `OpenAiClientManager`
  - Detects `HTTP 429 insufficient_quota`.
  - Writes `logs/openai_quota_status.properties`.
  - Temporarily skips OpenAI calls during the cooldown instead of repeatedly burning time on calls that will fail.
  - Local/statistical nightly policy promotion still continues when API quota is exhausted.

## New/changed environment variables

```text
OPENAI_NIGHTLY_REDUCE_CONTEXT_ENABLED=true
OPENAI_NIGHTLY_MAX_INPUT_CHARS=32000
OPENAI_NIGHTLY_EXAMPLES_PER_BUCKET=8
OPENAI_NIGHTLY_ENTRY_EXIT_MAX_OUTPUT_TOKENS=1200

OPENAI_DISABLE_WHEN_QUOTA_EXHAUSTED=true
OPENAI_QUOTA_COOLDOWN_MS=21600000
OPENAI_QUOTA_STATUS_PATH=logs/openai_quota_status.properties
```

## Why this matters

The nightly system was doing the right high-level work, but the OpenAI part was too expensive and failed with quota exhaustion. This patch makes OpenAI operate as a higher-level reasoning/review layer over clustered market/trade patterns instead of trying to evaluate every raw row.

The system still remains autonomous:
- local replay,
- statistical policy generation,
- validation-gated promotion,
- market representation,
- source evolution hooks,
- and next-day policy loading still continue even if OpenAI credits are exhausted.
