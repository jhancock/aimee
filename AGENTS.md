# Repository Guidelines

## Project Structure & Module Organization
- `src/aimee/` contains the library code.
- `src/aimee/chat/` contains the chat-completions pipeline:
  - `client.clj` request entrypoint/lifecycle
  - `executor.clj` HTTP execution
  - `options.clj` defaults and validation
  - `sse.clj` stream handler wiring
  - `parser.clj` payload parsing/accumulation
  - `emitter.clj` channel emission and backpressure
  - `timeout.clj` idle-timeout behavior
  - `events.clj` event constructors
- Low-level SSE framing/parsing is in `src/aimee/sse.clj` and `src/aimee/sse_parser.clj`.
- REPL/dev helpers are in `src/aimee/simulator.clj`, `src/aimee/stress.clj`, and `src/aimee/scheduler_simulator.clj`.
- No `test/` source set is used for standalone test execution at this stage.

## Build, REPL, and Development Commands
- `clojure -T:build jar` builds the library JAR into `target/`.
- `clojure -T:build deploy` builds and deploys to Clojars (credentials required).
- `clojure -M:nrepl` starts nREPL on port `7888`.
- Quick load check for core namespaces:
  - `clojure -M -e "(require 'aimee.chat.client 'aimee.sse 'aimee.scheduler)"`

## Testing Workflow (Current)
- There is intentionally no standalone automated test command yet.
- Validation is REPL-first through executable `(comment ...)` blocks in source files.
- Primary verification namespaces:
  - `src/aimee/simulator.clj`
  - `src/aimee/stress.clj`
  - `src/aimee/scheduler_simulator.clj`
- If automated tests are introduced later, favor minimal suites that do not constrain active code shaping.

## Coding Style & Naming Conventions
- Use standard Clojure formatting and 2-space indentation.
- Use kebab-case for namespaces/functions (file-level underscore exception: `src/aimee/sse_helpers.clj`).
- Prefer explicit state names (`stop?`, `stream-ref`, `terminated?`, `last-progress`).
- Caller-owned channels are passed via `:channel`; this library writes events and closes channel on terminal events.

## Architecture Notes
- Core flow: `start-request!` -> `executor` -> SSE consume/parse -> emit channel events.
- Event shape is always `{ :event <keyword> :data <payload> }`.
- Backpressure controls: `:overflow-mode` (`:queue` or `:block`) and `:overflow-max`.
- Timeout control: `:channel-idle-timeout-ms` emits `:complete` with `:reason :timeout` when delivery stalls.

## Documentation
- `docs/overview.md`: high-level map and workflow.
- `docs/api.md`: options and event contract.
- `docs/architecture.md`: runtime flow and subsystem behavior.
