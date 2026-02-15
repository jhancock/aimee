# Aimee Examples

REPL-focused usage examples. Each module contains executable `(comment ...)` blocks.

## Quick Start

```clojure
(require '[aimee.example.api :reload])
;; Evaluate expressions in the (comment ...) block at the bottom of each file
```

## Terminal Event `:reason` Values

| Reason | Meaning |
|--------|---------|
| `:done` | Normal completion |
| `:stopped` | User called `stop!` |
| `:timeout` | Channel idle timeout |
| `:eof` | Stream ended without `[DONE]` |

`:error` events do not include `:reason` — the exception is in `:data`.

## Module Index

| Module | Network | Description |
|--------|---------|-------------|
| `api.clj` | Yes | Non-streaming calls, HTTP error handling |
| `streaming.clj` | Yes | Streaming modes: standard, usage, raw, no-accumulate |
| `control.clj` | Yes | Stop requests, IO exception simulation |
| `backpressure.clj` | Mixed | Overflow modes, idle-timeout behavior |
| `parsing.clj` | No | Chat SSE parsing: usage, refusal, parse errors |
| `parser.clj` | No | Low-level SSE line parsing |
| `lifecycle.clj` | No | Channel lifecycle, callbacks |
| `scheduler.clj` | No | Timer scheduler, concurrent shutdown |

## Environment

- `OPENAI_API_KEY` — Required for network tests
- `OPENAI_API_URL` — Optional, defaults to OpenAI
