# Aimee Example Tests

REPL-focused example tests and usage demonstrations for the aimee library.

## Usage

Each module contains executable `(comment ...)` blocks with tests. Load a module and run the examples in the REPL:

```clojure
(require '[aimee.example.api :as api])

;; Run examples from the comment block
(comment
  (def event-1 (api/test-non-streaming-call!))
  event-1
  )
```

## Modules

### `core.clj`
Shared helpers and utilities used across examples:
- API key/url setup (`openai-api-url`, `openai-api-key`)
- `await-terminal-event!!` helper

### `api.clj`
API connection tests (requires `OPENAI_API_KEY`):
- Non-streaming API call
- Auth resolution and validation (no network)
- HTTP error flow (non-2xx responses)

### `streaming.clj`
Streaming behavior tests (requires `OPENAI_API_KEY`):
- Standard streaming with :parsed chunks and accumulation (default)
- Streaming with :include-usage? true
- Raw SSE proxy mode (:parse-chunks? false, :accumulate? false)
- Parsed chunks without accumulation (:accumulate? false)

### `parsing.clj`
Error handling and parsing edge cases (local SSE, no network):
- Non-chunk payload (should be skipped)
- Usage-only final chunk
- Refusal delta handling
- Parse error policy (:stop) with malformed JSON
- Parse error policy (:continue) with malformed JSON

### `lifecycle.clj`
Channel lifecycle and basic smoke tests (no network):
- Local channel smoke test
- Channel lifecycle guarantee on :complete
- Channel lifecycle guarantee on :error

### `backpressure.clj`
Overflow, timeout, backpressure scenarios:
- Overflow queue mode (:overflow-mode :queue)
- Block mode immediate backpressure (:overflow-mode :block)
- Idle-timeout after initial progress (requires `OPENAI_API_KEY`)
- Idle-timeout without consuming (requires `OPENAI_API_KEY`)
- Force idle-timeout by blocking consumer (requires `OPENAI_API_KEY`)
- No-timeout contrast (requires `OPENAI_API_KEY`)

### `control.clj`
Stop, shutdown control flow (requires `OPENAI_API_KEY`):
- Stop a streaming request and confirm :stopped

### `parser.clj`
Low-level SSE parser tests (no network):
- max-data-lines limit trigger
- Basic parsing behavior
- Edge cases (blank lines, comments, unknown fields)

### `scheduler.clj`
Scheduler lifecycle and concurrency tests (no network):
- Shutdown smoke test
- Concurrent shutdown thread safety test

## Environment Variables

Network-backed tests require:
- `OPENAI_API_KEY` - Your OpenAI API key
- `OPENAI_API_URL` or `OPENAI_URL` - Optional, defaults to `https://api.openai.com/v1/chat/completions`

## Running Tests

Load a module in the REPL and evaluate examples from the `(comment ...)` block at the bottom of each file.
