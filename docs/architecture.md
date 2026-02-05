# Architecture

## Runtime flow

### Non-streaming request

1. `aimee.chat.client/start-request!` validates and normalizes opts.
2. Emitter callbacks are created for the caller-owned channel.
3. Worker runs via `core.async/io-thread`.
4. `aimee.chat.executor/non-streaming` performs one HTTP POST.
5. Parsed final response is emitted as `:complete` (or `:error` for non-2xx).

### Streaming request

1. `start-request!` initializes `stop?`, `stream-ref`, `terminated?`, callbacks.
2. Optional idle-timeout monitor starts (`aimee.chat.timeout/start-idle-timeout!`).
3. Worker runs `aimee.chat.executor/streaming`.
4. HTTP body stream is consumed by `aimee.sse/consume-sse!`.
5. `aimee.sse-parser/step` parses SSE frames.
6. `aimee.chat.sse/make-stream-handlers` adapts raw SSE events into channel events.
7. `aimee.chat.parser/*` parses payload JSON and accumulates completion info.
8. Emitter sends `:chunk` events, then `:complete`.

## Event pipeline

`InputStream` -> `aimee.sse` -> `aimee.sse-parser` -> `aimee.chat.sse` -> `aimee.chat.emitter` -> caller channel

## Backpressure model

`aimee.chat.emitter` supports two modes:

- `:block`: write blocks immediately on full channel
- `:queue`: lazily allocates bounded overflow queue (`LinkedBlockingQueue`) and drains in a background thread

Progress timestamps are only updated when writes to the caller channel succeed. Idle-timeout logic uses this to detect stalled delivery.

## Timeout model

`aimee.chat.timeout/start-idle-timeout!` schedules periodic checks with `aimee.scheduler/schedule-fixed-delay!`.

On timeout:

- emits `{:event :complete :data {:content "" :reason :timeout}}`
- marks request terminated
- invokes `stop-fn` to close the active stream

## Parsing responsibilities

- `aimee.sse-parser`: protocol framing (`data:`, `event:`, `id:`, blank-line boundaries, EOF)
- `aimee.chat.parser`: OpenAI chat payload parsing, refusal normalization, content/metadata accumulation

## Scheduler lifecycle

`aimee.scheduler` uses one daemon scheduled executor.

- Timers are registered under UUID keys.
- A shutdown task is scheduled when timer count reaches zero.
- Executor is shut down after idle delay (`*shutdown-idle-ms*`, default 60000 ms).

## Development and validation

There is currently no standalone test suite alias.

Expected validation workflow is REPL-first with `(comment ...)` blocks in source modules. `simulator.clj`, `stress.clj`, and `scheduler_simulator.clj` contain most scenario coverage.
