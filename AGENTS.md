# Repository Guidelines

## Project Structure & Module Organization
- `src/aimee/` contains the library code.
- `src/aimee/chat/` implements the chat-completions flow: `client.clj` (entry point), `executor.clj` (HTTP), `parser.clj` (SSE chunk parsing), `emitter.clj` (channel delivery), `timeout.clj` (idle timeout), and `events.clj` (event shapes).
- Low-level SSE parsing is in `src/aimee/sse_parser.clj` and `src/aimee/sse.clj`.
- Simulators and load helpers live in `src/aimee/*simulator*.clj` and `src/aimee/stress.clj` and are excluded from the build artifact.
- No `test/` directory is present yet.

## Build, Test, and Development Commands
- `clojure -T:build jar` builds the library JAR into `target/` (see `build.clj`).
- `clojure -T:build deploy` builds and deploys to Clojars (requires credentials).
- `clojure -M:nrepl` starts a REPL with nREPL on port `7888` (see `deps.edn`).
- There is no test alias configured yet.

## Coding Style & Naming Conventions
- Clojure source uses standard formatting and 2-space indentation.
- Use kebab-case for namespaces and functions (exception: `src/aimee/sse_helpers.clj`).
- Private helpers should be prefixed with `-`.
- Prefer explicit names for state and channels (e.g., `stop?`, `stream-ref`, `terminated?`).
- Caller-owned channels are passed in via `:channel`; this library only writes events and closes channels on completion/error.

## Testing Guidelines
- Tests are not yet present. If you add them, prefer `clojure.test` in `test/` with `*-test.clj` file names.
- Focus tests on event emission (`:chunk`, `:complete`, `:error`) and backpressure behavior in `emitter.clj`.

## Commit & Pull Request Guidelines
- Commit history is minimal and does not show a strict convention. Use clear, imperative subjects (e.g., "Add SSE timeout handling").
- PRs should include a short summary, testing notes (even if "not run"), and linked issues when applicable.

## Architecture Notes
- Core flow: `start-request!` → `executor/*` HTTP request → SSE consumption → parse → emit to channel.
- Event payloads are maps with `:event` and `:data`. Streaming chunks may include `:parsed` when `:parse-chunks?` is true.
- Backpressure is handled by `:overflow-mode` (`:block` or `:queue`) and `:overflow-max`.
