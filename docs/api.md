# API Reference

## API Surface

Public API:

- `aimee.chat.client/start-request!`
- `aimee.chat.options/defaults`
- `aimee.sse-helpers/format-sse-data`, `format-sse-done`, `event->simplified-sse`

REPL walk through:

- `src/aimee/example/*` — REPL examples

All other namespaces are internal implementation.

## Entry Point

```clojure
(aimee.chat.client/start-request! opts)
;; => {:stop! (fn [])}
```

Calling `:stop!` cancels the request and emits `:complete` with `:reason :stopped`.

## Required Options

- **`:channel`** — Caller-created `core.async` channel
- **`:url`** — OpenAI-compatible endpoint
- **`:model`** — Model ID string
- **`:messages`** — Non-empty sequence of chat messages
- **API credentials** — One of: `:api-key`, `:api-key-fn`, `:headers` with Authorization

## Optional Options

- **`:stream?`** — `false` — Enable streaming
- **`:api-key`** — `nil` — API key string
- **`:api-key-fn`** — `nil` — Function returning API key
- **`:api-key-env`** — `"OPENAI_API_KEY"` — Env var name for key
- **`:parse-chunks?`** — `true` — Include `:parsed` in chunks
- **`:accumulate?`** — `true` — Accumulate content in `:complete`
- **`:on-parse-error`** — `:stop` — `:stop` or `:continue`
- **`:overflow-max`** — `10000` — Max queued events
- **`:overflow-mode`** — `:queue` — `:queue` or `:block`
- **`:channel-idle-timeout-ms`** — `nil` — Idle timeout
- **`:http-timeout-ms`** — `nil` — HTTP timeout
- **`:headers`** — `nil` — Extra headers
- **`:include-usage?`** — `false` — Include usage in streaming

## Event Contract

All events have shape `{:event <keyword> :data <payload>}`.

### `:chunk`

Streaming content delta.

```clojure
{:event :chunk
 :data {:id "..." :type "..." :data "{...}" :parsed {...}}}
```

`(:parsed (:data event))` includes:

- **`:content`** — Delta text
- **`:role`** — Role string
- **`:tool-calls`** — Tool definitions
- **`:api-finish-reason`** — `"stop"`, `"length"`, `"content_filter"`, `"tool_calls"`
- **`:usage`** — Token counts
- **`:done?`** — Terminal chunk flag

### `:complete`

Request finished.

```clojure
{:event :complete
 :data {:content "..." :reason :done :api-finish-reason "stop" ...}}
```

- **`:content`** — Accumulated text
- **`:reason`** — `:done`, `:stopped`, `:timeout`, `:eof`
- **`:api-finish-reason`** — Passthrough from OpenAI
- **`:role`, `:tool-calls`, `:usage`, `:refusal`, `:refusal?`** — As returned by API

### `:error`

Request failed. `:data` is an exception.

## Streaming Behavior

- **`:parse-chunks? false`** — No `:parsed` key in chunks
- **`:accumulate? false`** — No content accumulation
- **`:on-parse-error :stop`** — Emit `:error`, close stream
- **`:on-parse-error :continue`** — Log warning, skip chunk

## Backpressure

- **`:queue`** — Lazy overflow queue up to `:overflow-max`
- **`:block`** — Block producer on full channel

## Idle Timeout

When `:channel-idle-timeout-ms` is set and no progress for that duration:

- Emits `:complete` with `:reason :timeout`
- Closes stream

## SSE Helpers

`aimee.sse-helpers` provides utilities for browser-friendly SSE:

- `format-sse-data` — Format data as SSE
- `format-sse-done` — Format `[DONE]` sentinel
- `event->simplified-sse` — Convert event to SSE string
