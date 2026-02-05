# Architecture

## Request Flow

### Non-Streaming

```
start-request!
    ├─> validate opts (options/validate-opts!)
    ├─> create channel callbacks (emitter/make-channel-callbacks)
    ├─> start idle timeout (timeout/start-idle-timeout!)
    └─> async/io-thread
          └─> executor/non-streaming
                ├─> build HTTP request
                ├─> http/post (as :string)
                └─> parse response
                      └─> emit :complete event
```

### Streaming

```
start-request!
    ├─> validate opts
    ├─> create channel callbacks
    ├─> start idle timeout
    └─> async/io-thread
          └─> executor/streaming
                ├─> build HTTP request
                ├─> http/post (as :stream)
                ├─> create SSE handlers (chat-sse/make-stream-handlers)
                └─> sse/consume-sse!
                      ├─> read lines via BufferedReader
                      ├─> parse SSE (sse-parser/step)
                      ├─> parse JSON (chat.parser/parse-sse-event!)
                      ├─> accumulate content (when :accumulate?)
                      └─> emit events via emitter
                            ├─> write to channel (with overflow handling)
                            └─> update last-progress (for idle timeout)
```

---

## Module Details

### `aimee.chat.client`

**Responsibility:** Entry point, request lifecycle management

- Validates options via `aimee.chat.options/validate-opts!`
- Creates channel callbacks via `aimee.chat.emitter/make-channel-callbacks`
- Starts idle timeout monitor via `aimee.chat.timeout/start-idle-timeout!`
- Spawns worker thread via `async/io-thread`
- Returns `{:stop! fn}` for cancellation

**Key Design:**
- Caller owns the channel - library only writes to it
- `stop?` and `stream-ref` atoms for cancellation
- `terminated?` atom for coordinated shutdown

---

### `aimee.chat.executor`

**Responsibility:** HTTP request execution

- `build-body` - Constructs JSON request body
- `build-request-opts` - Builds HTTP options (headers, auth, timeout)
- `non-streaming` - Executes single POST request, parses full response
- `streaming` - Executes streaming POST, wires up SSE consumption

**Key Design:**
- Separation between HTTP concerns and SSE parsing
- Stream ref passed to callbacks for proper cleanup on error/stop

---

### `aimee.chat.emitter`

**Responsibility:** Channel event emission with overflow handling

- `make-emitter` - Creates emitter function with overflow state
- `make-channel-callbacks` - Creates `:emit!`, `:complete!`, `:error!` callbacks
- Overflow queue created on-demand when channel is full
- Drain thread writes queued events to channel

**Overflow Handling Flow:**

```
emit! called
    │
    ├─ queue exists?
    │   └─ Yes ──> put in queue
    │
    ├─ channel has capacity? (offer!)
    │   └─ Yes ──> write directly, update last-progress
    │
    ├─ overflow-mode :block?
    │   └─ Yes ──> block on >!!, update last-progress
    │
    └─ create overflow queue, drain thread, put in queue
```

**Key Design:**
- `last-progress` atom updated on successful writes (used for idle timeout)
- Overflow queue is bounded (`LinkedBlockingQueue` with `overflow-max`)
- Drain thread exits if write fails (closes channel)

---

### `aimee.chat.parser`

**Responsibility:** Parse OpenAI SSE and response payloads

- `parse-sse-event` - Parse SSE data, returns `:content`, `:done?`, `:skip?`, etc.
- `parse-sse-event!` - Throwing version
- `parse-final-response` - Parse non-streaming response body
- `accumulate-content` - Accumulate deltas with content
- `accumulate-metadata` - Accumulate only metadata (no content)

**Key Design:**
- Refusal handling: refusal content normalized into `:content` when content is blank
- Non-chat events filtered via `:skip?` flag
- Metadata (`:role`, `:tool-calls`, `:function-call`, `:usage`) captured separately

---

### `aimee.chat.sse`

**Responsibility:** Bridge between SSE consumer and channel emitter

- `make-stream-handlers` - Creates `:on-event`, `:on-complete`, `:on-error` callbacks

**Flow:**

```
sse/consume-sse! emits raw event
    │
    ├─> :on-event (chat-sse)
    │     ├─ parse JSON (parse-sse-event!)
    │     ├─ attach :parsed to event
    │     └─ emit! as :chunk
    │
    ├─> :on-complete (chat-sse)
    │     ├─ close stream
    │     └─ complete! emit
    │
    └─> :on-error (chat-sse)
          ├─ close stream
          └─ error! emit
```

**Key Design:**
- Validates JSON format (throws on error if `:on-parse-error` is `:stop`)
- Skips events with `:skip?` flag (non-chat events)
- Skips terminal `[DONE]` events with empty content

---

### `aimee.sse-parser`

**Responsibility:** SSE line-by-line parsing state machine

- `parse-line` - Parse a single SSE line
- `empty-state` - Initial parser state
- `step` - Process a parsed line, return updated state + actions

**State Machine:**

```
State: {:data-lines [...]
        :raw-lines [...]
        :event-id "..."
        :event-type "..."}

Step actions:
    ├─ :state - updated state
    ├─ :acc - updated accumulator
    ├─ :event - event map (when blank line or EOF)
    ├─ :flush - :blank-line or :eof
    ├─ :complete - :done or :eof
    ├─ :done-event - original [DONE] event
    └─ :unrecognized? - true for unknown lines
```

**Key Design:**
- Blank line triggers event emission
- Comments (`:` prefix) are captured in raw lines but ignored
- Unknown fields trigger `:unrecognized?` for logging

---

### `aimee.sse`

**Responsibility:** Low-level SSE consumption from InputStream

- `consume-sse!` - Read SSE stream, call callbacks
- Handles buffered reading via `BufferedReader`
- Detects incomplete streams (missing `[DONE]`)
- Supports accumulator function for incremental content building
- Optional raw line capture for debugging

**Key Design:**
- `terminated?` atom for coordinated shutdown (doesn't call callbacks if already terminated)
- `stop?` atom for cancellation via `stop!` function
- Catches exceptions and calls `:on-error`

---

### `aimee.scheduler`

**Responsibility:** Scheduled executor service with auto-shutdown

- `schedule-fixed-delay!` - Schedule recurring task
- `status` - Return scheduler state snapshot
- Auto-shutdowns when idle for `*shutdown-idle-ms*` (default 60s)

**Key Design:**
- Single daemon thread named `"aimee-scheduler"`
- Tracks active timers in map by UUID
- Schedules shutdown task when timer count reaches zero
- Shutdown task checks timer count again before executing

---

### `aimee.chat.timeout`

**Responsibility:** Idle timeout monitoring

- `start-idle-timeout!` - Start periodic idle checks
- Checks `last-progress` from emitter
- Emits `:complete` with `:reason :timeout` when exceeded
- Cancels itself on timeout or termination

**Key Design:**
- Checks at `min(1000, channel-idle-timeout-ms)` intervals
- Only time when successful writes count as progress
- Sets `terminated?` and calls `stop-fn` on timeout

---

## Thread Model

| Thread | Purpose | Lifecycle |
|--------|---------|-----------|
| Caller thread | Calls `start-request!`, creates channel | User-controlled |
| `async/io-thread` | HTTP request + SSE consumption | Per request |
| Overflow drain thread | Drains overflow queue to channel | Created on-demand |
| Scheduler thread | Idle timeout checks | Singleton, daemon |
| Timeout check thread | Periodic idle progress checks | Per request (when timeout enabled) |

---

## Resource Cleanup

### Streams
- Closed in `:on-complete` and `:on-error` handlers
- Closed on `stop!` invocation
- Closed on HTTP error

### Channels
- Closed by emitter on `:complete!` or `:error!` calls
- Caller owns and should close if needed (though library typically closes)

### Scheduler
- Auto-shutdowns when idle for 60 seconds
- Can be bound via `*shutdown-idle-ms*` dynamic var

### Threads
- `io-thread` exits after request completes
- Drain thread exits if write fails or channel closes
- Timeout threads self-cancel on completion/timeout/termination

---

## Known Issues / Areas for Improvement

1. **Code duplication** in `chat/parser.clj`:
   - `parse-sse-event` and `parse-sse-event!` nearly identical
   - `accumulate-content` and `accumulate-metadata` share logic

2. **Race condition** in emitter: multiple threads could race to create overflow queue (though functional correctness is maintained)

3. **File naming inconsistency**: `sse_helpers.clj` uses underscore while others use kebab-case

4. **`http.clj` wrapper**: thin wrapper around `babashka.http-client`, may be unnecessary

5. **Double `@stop?` check** in `executor.clj` (lines 55 and 62)

6. **Skip logic** in `chat/sse.clj:36-37`: filters out terminal events with empty content
