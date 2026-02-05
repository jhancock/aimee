# Aimee Overview

Aimee is a `core.async`-first client for OpenAI-compatible chat completions.

## Design constraints

- Caller owns the channel.
- Library emits only event maps: `:chunk`, `:complete`, `:error`.
- Streaming behavior is configurable, but event shape stays stable.
- Local verification is REPL-driven via `(comment ...)` blocks in source files.

## Source layout

`src/aimee/chat/`
- `client.clj`: entry point (`start-request!`) and lifecycle wiring
- `executor.clj`: HTTP execution for streaming and non-streaming
- `options.clj`: defaults + `clojure.spec` validation
- `sse.clj`: stream handlers bridging SSE consumer to channel events
- `parser.clj`: OpenAI payload parsing and accumulation helpers
- `emitter.clj`: channel emission + overflow handling
- `timeout.clj`: channel-idle timeout monitor
- `events.clj`: event constructors

`src/aimee/`
- `sse_parser.clj`: low-level SSE line parser/state machine
- `sse.clj`: InputStream SSE consume loop
- `http.clj`: thin HTTP wrapper (`babashka.http-client`)
- `scheduler.clj`: shared scheduled executor with idle shutdown
- `sse_helpers.clj`: helper conversion to simplified browser-facing SSE
- `simulator.clj`, `stress.clj`, `scheduler_simulator.clj`: REPL helpers and stress tools

## Build and dev

- Build jar: `clojure -T:build jar`
- Deploy: `clojure -T:build deploy`
- Start nREPL: `clojure -M:nrepl`

## Testing model

There is intentionally no standalone test runner alias today.

Validation is done in REPL sessions with executable `(comment ...)` blocks in each namespace, especially:

- `src/aimee/simulator.clj`
- `src/aimee/stress.clj`
- `src/aimee/scheduler_simulator.clj`

## Additional docs

- `docs/api.md`: request options and event contract
- `docs/architecture.md`: execution flow and subsystem behavior
