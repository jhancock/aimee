# Aimee Examples

REPL-focused usage examples. Each module contains executable `(comment ...)` blocks.

## Quick Start

```clojure
(require '[aimee.example.api :reload])
;; Evaluate expressions in the (comment ...) block at the bottom of each file
```

## Terminal Event `:reason` Values

- **`:done`** — Normal completion
- **`:stopped`** — User called `stop!`
- **`:timeout`** — Channel idle timeout
- **`:eof`** — Stream ended without `[DONE]`

`:error` events do not include `:reason` — the exception is in `:data`.

## Module Index

**Network required:**

- **`api.clj`** — Non-streaming calls, HTTP error handling
- **`streaming.clj`** — Streaming modes: standard, usage, raw, no-accumulate
- **`control.clj`** — Stop requests, IO exception simulation

**Mixed (some network):**

- **`backpressure.clj`** — Overflow modes, idle-timeout behavior

**No network (local only):**

- **`parsing.clj`** — Chat SSE parsing: usage, refusal, parse errors
- **`parser.clj`** — Low-level SSE line parsing
- **`lifecycle.clj`** — Channel lifecycle, callbacks
- **`scheduler.clj`** — Timer scheduler, concurrent shutdown

## Environment

- `OPENAI_API_KEY` — Required for network tests
- `OPENAI_API_URL` — Optional, defaults to OpenAI
