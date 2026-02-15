# Architecture

## Design Principle: Two-Layer Event Model

Events provide two orthogonal layers of information:

**Layer 1: Lifecycle events** — What is happening?

- **`:chunk`** — Content delta arriving
- **`:complete`** — Request finished successfully
- **`:error`** — Request failed

**Layer 2: API passthrough data** — Why / what details?

- **`:reason`** — Library: `:done`, `:stopped`, `:timeout`, `:eof`
- **`:api-finish-reason`** — OpenAI: `"stop"`, `"length"`, `"content_filter"`, `"tool_calls"`
- **`:content`** — OpenAI: Accumulated text
- **`:tool-calls`** — OpenAI: Tool definitions
- **`:usage`** — OpenAI: Token counts

Consumers can:
- Handle just Layer 1 for simple flows
- Dig into Layer 2 when they need nuance

## Terminal Event `:reason` Values

Every `:complete` event includes `:reason`:

- **`:done`** — Normal completion
- **`:stopped`** — User called `stop!`
- **`:timeout`** — Channel idle timeout exceeded
- **`:eof`** — Stream ended without `[DONE]` sentinel

`:error` events do not include `:reason` — the exception is in `:data`.

## Runtime Flow

### Non-streaming request

1. `aimee.chat.client/start-request!` validates and normalizes opts
2. Emitter callbacks created for caller-owned channel
3. Worker runs via `core.async/io-thread`
4. `aimee.chat.executor/non-streaming` performs one HTTP POST
5. Parsed response emitted as `:complete` (or `:error` for non-2xx)

### Streaming request

1. `start-request!` initializes `stop?`, `stream-ref`, `terminated?`, callbacks
2. Optional idle-timeout monitor starts (`aimee.chat.timeout`)
3. Worker runs `aimee.chat.executor/streaming`
4. HTTP body stream consumed by `aimee.sse/consume-sse!`
5. `aimee.sse-parser/step` parses SSE frames
6. `aimee.chat.sse/make-stream-handlers` adapts raw events to channel events
7. `aimee.chat.parser` parses JSON and accumulates content
8. Emitter sends `:chunk` events, then `:complete`

## Event Pipeline

```
InputStream → aimee.sse → aimee.sse-parser → aimee.chat.sse → aimee.chat.emitter → caller channel
```

## Backpressure

`aimee.chat.emitter` supports two modes:

- **`:block`** — Write blocks immediately on full channel
- **`:queue`** — Lazily allocates bounded overflow queue, drains in background thread

Progress timestamps update only on successful channel writes. Idle-timeout uses this to detect stalled delivery.

## Request Control

### `stop!`

Each request returns a map with `:stop!` function:

```clojure
(def result (chat/start-request! {...}))
((:stop! result))  ;; Cancels the request
```

On stop:
- Sets `stop?` atom
- Closes HTTP stream
- Emits `:complete` with `:reason :stopped`
- Accumulated content preserved

### Idle Timeout

`aimee.chat.timeout/start-idle-timeout!` schedules periodic checks.

On timeout:
- Emits `{:event :complete :data {:content "" :reason :timeout}}`
- Marks request terminated
- Closes active stream

## Parsing Responsibilities

- **`aimee.sse-parser`** — SSE protocol framing (`data:`, `event:`, blank lines, EOF)
- **`aimee.chat.parser`** — OpenAI payload parsing, refusal normalization, content/metadata accumulation

## Scheduler Lifecycle

`aimee.scheduler` uses one daemon scheduled executor:

- Timers registered under UUID keys
- Shutdown task scheduled when timer count reaches zero
- Executor shuts down after idle delay (`*shutdown-idle-ms*`, default 60000ms)

## Validation

REPL-first with `(comment ...)` blocks in `src/aimee/example/`.
