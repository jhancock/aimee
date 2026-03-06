# AI Assistant Guidelines

## Development Environment

Use `clj-nrepl-eval` to evaluate Clojure code via the running nREPL server on port 7888.

**Check nREPL availability:**
```bash
clj-nrepl-eval --discover-ports
```

**Evaluate code (always use `:reload` to pick up changes):**
```bash
clj-nrepl-eval -p 7888 "(require '[aimee.chat.client :as chat] :reload)"
```

## Key Concepts

**Event shape:** `{:event <keyword> :data <payload>}` — defined in `aimee.chat.events`

**Event types:**
- `:chunk` — Streaming delta
- `:complete` — Terminal success (includes `:reason`: `:done`, `:stopped`, `:timeout`, `:eof`)
- `:error` — Failure (exception in `:data`)

**Core flow:**
- `aimee.chat.client/start-request!` — Entry point, validates opts, initializes lifecycle
- `aimee.chat.executor` — HTTP execution (streaming/non-streaming)
- `aimee.sse/consume-sse!` — SSE stream consumption
- `aimee.sse-parser` — SSE frame parsing
- `aimee.chat.parser` — OpenAI payload parsing, content accumulation
- `aimee.chat.emitter` — Channel emission with backpressure

**Backpressure modes** (`aimee.chat.emitter`):
- `:queue` — Bounded overflow queue, background drain
- `:block` — Immediate blocking on full channel

## Testing

No automated tests. Validate via REPL evaluation in `src/aimee/example/` files.
