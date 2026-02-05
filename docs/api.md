# API Reference

## Main Entry Point

### `aimee.chat.client/start-request!`

```clojure
(start-request! opts)
```

Start a chat completion request.

**Required Options:**

| Key | Type | Description |
|-----|------|-------------|
| `:channel` | `core.async/channel` | Caller-created channel for events |
| `:url` | string | OpenAI-compatible API endpoint |
| `:api-key` | string | API bearer token |
| `:model` | string | Model identifier (e.g., `"gpt-4o-mini"`) |
| `:messages` | vector | Chat messages `[{:role "user" :content "..."}]` |

**Optional Options:**

| Key | Default | Description |
|-----|---------|-------------|
| `:stream?` | `false` | Enable SSE streaming |
| `:parse-chunks?` | `true` | Include `:parsed` key in `:chunk` events |
| `:accumulate?` | `true` | Build `:content` in `:complete` event |
| `:on-parse-error` | `:stop` | `:stop` to error, `:continue` to skip bad chunks |
| `:overflow-max` | `10000` | Max queued events before overflow |
| `:overflow-mode` | `:queue` | `:queue` buffers, `:block` applies backpressure |
| `:channel-idle-timeout-ms` | `nil` | Abort if no progress for N ms |
| `:http-timeout-ms` | `nil` | HTTP request timeout |
| `:headers` | `nil` | Additional HTTP headers |
| `:include-usage?` | `false` | Request usage stats in streaming mode |

**Returns:** `{:stop! fn}` - Call `(stop!)` to cancel the request.

---

## Channel Events

Events are emitted to the provided channel as maps with `:event` and `:data` keys.

### `:chunk` Event

```clojure
{:event :chunk
 :data {:id "..."
        :type "..."
        :data "{...}"  ; Raw JSON string
        :parsed {...}} ; When :parse-chunks? is true
```

The `:parsed` map (when `:parse-chunks?` is true):

```clojure
{:content "..."            ; Delta content
 :role "assistant"         ; When present
 :tool-calls [...]         ; When present
 :function-call {...}      ; When present
 :finish-reason "stop"     ; When present
 :usage {...}              ; When present
 :done? false              ; True for terminal chunk
 :refusal "..."            ; When refusal present
 :refusal? true}           ; True if refusal occurred
```

### `:complete` Event

```clojure
{:event :complete
 :data {:content "..."        ; Accumulated content (when :accumulate?)
        :finish-reason "stop"
        :role "assistant"
        :tool-calls [...]
        :function-call {...}
        :usage {...}
        :refusal "..."
        :refusal? true
        :reason :done          ; :done, :stopped, :timeout, :error
        :done-event {...}}}    ; Original [DONE] event
```

### `:error` Event

```clojure
{:event :error
 :data <Exception or ex-info>}
```

---

## Streaming Behavior Options

### `:parse-chunks?` (default: `true`)

When `true`, each `:chunk` event includes a `:parsed` key with structured data extracted from the SSE payload.

When `false`, chunks contain only raw SSE data (`:id`, `:type`, `data`) without parsing. Useful for raw proxying.

### `:accumulate?` (default: `true`)

When `true`, the `:complete` event includes `:content` built from accumulated chunks.

When `false`, `:content` is empty but metadata (`:finish-reason`, `:role`, etc.) is still captured.

### `:on-parse-error` (default: `:stop`)

- `:stop` - Emit `:error` event and close stream on parse failure
- `:continue` - Log warning and skip malformed chunks

---

## Backpressure Handling

### `:overflow-max` (default: `10000`)

Maximum number of events that can be queued when the consumer is slow.

### `:overflow-mode` (default: `:queue`)

- `:queue` - Create an in-memory overflow queue when channel is full. Events are drained by a background thread.
- `:block` - Block immediately when channel is full, applying direct backpressure to the producer.

---

## Timeout Options

### `:channel-idle-timeout-ms`

Aborts the request if no events are successfully emitted to the channel for the specified duration. Note that enqueuing into the overflow buffer does **not** count as progress.

### `:http-timeout-ms`

HTTP request timeout in milliseconds. Passes through to the underlying HTTP client.

---

## Event Constructors

### `aimee.chat.events/make-event`

```clojure
(make-event kind payload)
```

Create a consistent event map. `kind` is `:complete`, `:error`, `:chunk`, or a custom keyword.

---

## SSE Helpers

### `aimee.sse-helpers/format-sse-data`

```clojure
(format-sse-data payload)
```

Return an SSE data frame with a JSON payload for browser clients.

### `aimee.sse-helpers/format-sse-done`

```clojure
(format-sse-done)
```

Return an SSE data frame signaling stream completion (`data: [DONE]\n\n`).

### `aimee.sse-helpers/event->simplified-sse`

```clojure
(event->simplified-sse item)
```

Convert a channel stream event into a simplified SSE data frame. Returns `nil` for empty content or errors.
