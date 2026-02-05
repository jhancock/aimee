# Aimee - Project Overview

**Aimee** is a core.async-first streaming client for LLM APIs (OpenAI-compatible chat completions).

## Core Design Philosophy

- **Caller owns the channel** - The library writes events into a caller-provided `core.async` channel and never allocates channels internally
- **Consistent event model** - All responses emit `:chunk`, `:complete`, or `:error` events
- **Streaming-first** - Built around Server-Sent Events (SSE) streaming, with non-streaming as a fallback

## Module Structure

```
src/aimee/
├── chat/           # Chat completion API (OpenAI-compatible)
│   ├── client.clj      # Main entry point: start-request!
│   ├── executor.clj    # HTTP execution (streaming/non-streaming)
│   ├── emitter.clj     # Channel event emission with overflow handling
│   ├── parser.clj      # SSE response parsing
│   ├── sse.clj         # SSE handler factory
│   ├── options.clj     # Validation and defaults
│   ├── timeout.clj     # Idle timeout handling
│   └── events.clj      # Event constructors
├── sse.clj         # Low-level SSE consumer
├── sse-parser.clj  # SSE parsing state machine
├── sse_helpers.clj # SSE formatting helpers
├── scheduler.clj   # Scheduled executor service
├── http.clj        # HTTP client wrapper
└── util.clj        # Thread info utilities
```

## Key Features

| Feature | Description |
|---------|-------------|
| **Backpressure** | `:overflow-max` and `:overflow-mode` (`:queue` or `:block`) |
| **Timeouts** | HTTP timeout and channel idle timeout |
| **Parse errors** | Configurable `:stop` or `:continue` on malformed chunks |
| **Refusal handling** | Refusal content normalized into `:content` with `:refusal?` flag |
| **Metadata** | Supports `:role`, `:tool-calls`, `:function-call`, `:usage` |
| **Resource cleanup** | Auto-shutdown scheduler when idle, proper stream closure |

## Dependencies

- `org.clojure/clojure` - 1.12.4
- `org.clojure/core.async` - 1.9.829-alpha2
- `org.babashka/http-client` - 0.4.23
- `cheshire/cheshire` - 6.1.0 (JSON)
- `org.clojure/tools.logging` - 1.3.0

## Quick Start

```clojure
(require '[aimee.chat.client :as chat]
         '[clojure.core.async :as async])

(def ch (async/chan 64))

(def result
  (chat/start-request!
   {:url "https://api.openai.com/v1/chat/completions"
    :api-key "sk-..."
    :model "gpt-4o-mini"
    :messages [{:role "user" :content "Hello!"}]
    :channel ch
    :stream? true}))

(loop []
  (when-let [event (async/<!! ch)]
    (case (:event event)
      :chunk (println "Chunk:" (:parsed (:data event)))
      :complete (println "Done:" (:data event))
      :error (println "Error:" (:data event))
      (recur))))
```

## Documentation

- [API Reference](./api.md) - Detailed API documentation
- [Architecture](./architecture.md) - Deep dive into internals
- [Streaming Behavior](./aimee-streaming-spec.md) - Streaming specification
- [Responses Spec](./aimee-responses-spec.md) - Response format specification
