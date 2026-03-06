# aimee

Streaming Chat Completions over core.async channels.

## Install

```edn
{jhancock/aimee {:mvn/version "0.1.0-SNAPSHOT"}}
```

## Quick Start

```clojure
(require '[aimee.chat.client :as chat]
         '[clojure.core.async :as async])

(def ch (async/chan 64))
(def result (chat/start-request!
             {:url "https://api.openai.com/v1/chat/completions"
              :api-key "sk-..."
              :channel ch
              :model "gpt-5-mini"
              :stream? true
              :messages [{:role "user" :content "Hello!"}]}))

;; result is a map containing a stop function. Call it to cancel the request.
;; ((:stop! result)) 

(async/go-loop []
  (when-let [event (async/<! ch)]
    (case (:event event)
      :chunk
      (do
        (prn "Event" event)
        (recur))

      :complete
      (prn "Event" event)

      :error
      (prn "Event" event))))
```

## API

### `start-request!`

```clojure
(aimee.chat.client/start-request! opts)
;; => {:stop! (fn [])}
```

Calling `:stop!` cancels the request and emits `:complete` with `:reason :stopped`.

### Required Options

- **`:channel`** — Caller-created `core.async` channel
- **`:url`** — OpenAI-compatible endpoint
- **`:model`** — Model ID string
- **`:messages`** — Non-empty sequence of chat messages
- **Auth** — One of: `:api-key`, `:api-key-fn`, or `:headers` with Authorization

### Optional Options

- **`:stream?`** — `false` — Enable streaming response
- **`:accumulate?`** — `true` — Accumulate content in `:complete`
- **`:backpressure`** — `:queue` — `:queue` or `:block`
- **`:queue-capacity`** — `1000` — Capacity of overflow queue when :backpressure is :queue
- **`:channel-idle-timeout-ms`** — `nil` — Abort if no progress for this duration
- **`:http-timeout-ms`** — `nil` — HTTP request timeout
- **`:include-usage?`** — `false` — Include usage stats in streaming `:complete`
- **`:on-parse-error`** — `:stop` — `:stop` emits error and closes; `:continue` logs and skips

For full defaults and descriptions, see [`aimee.chat.options/defaults`](src/aimee/chat/options.clj).

## Events

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
- **`:usage`** — Token counts (when available)
- **`:done?`** — Terminal chunk flag

### `:complete`

Request finished.

```clojure
{:event :complete
 :data {:content "..." :reason :done :api-finish-reason "stop" ...}}
```

- **`:content`** — Accumulated text (when `:accumulate? true`)
- **`:reason`** — `:done`, `:stopped`, `:timeout`, `:eof`
- **`:api-finish-reason`** — Passthrough from API
- **`:role`, `:tool-calls`, `:usage`, `:refusal`, `:refusal?`** — As returned by API

### `:error`

Request failed. `:data` is an exception.

## Backpressure

- **`:queue`** — Creates bounded overflow queue (capacity set by `:queue-capacity`), drains in background thread
- **`:block`** — Blocks producer immediately when channel is full

Progress timestamps update only on successful channel writes. Idle timeout uses this to detect stalled delivery.

## SSE Helpers

`aimee.sse-helpers` provides utilities for browser-friendly SSE:

- `format-sse-data` — Format a map as an SSE frame: `data: {...}\n\n`
- `format-sse-done` — Format the `[DONE]` sentinel: `data: [DONE]\n\n`
- `event->simplified-sse` — Convert a channel event to an SSE frame string

### `event->simplified-sse`

Converts channel events to SSE frames for streaming to browsers:

- `:chunk` with content → `data: {"text":"..."}\n\n`
- `:complete` → returns `nil` (use `format-sse-done` explicitly)
- `:error` → returns `nil`

### HTTP Bridge Pattern

```clojure
(loop []
  (when-let [event (async/<!! ch)]
    (when-let [frame (sse-helpers/event->simplified-sse event)]
      (write-frame frame))
    (when-not (#{:complete :error} (:event event))
      (recur))))
;; After terminal event, signal stream end
(write-frame (sse-helpers/format-sse-done))
```

## Docs & Examples

- **[docs/architecture.md](docs/architecture.md)** — Design principles, runtime flow
- **[src/aimee/example/](src/aimee/example/)** — REPL examples for streaming, parsing, backpressure, lifecycle

## Build

```sh
clojure -T:build jar
```

## Publish

```sh
clojure -T:build deploy
```

## Example Chat Server

Bare-bones HTTP example app that serves a one-page Deep Chat client at `/chat` and streams responses through `aimee.chat.client`.

### Requirements

- `OPENAI_API_KEY` must be set
- `OPENAI_API_URL` is optional (defaults to OpenAI)
- Model default is `gpt-5-mini`

### Run

```sh
clojure -M:chat-server
```

Then open http://localhost:8080/chat

Optional custom port:

```sh
clojure -M:chat-server -- 8090
```

### Simple SSE Test Endpoint

Use `POST /chat/simple` to test streaming with plain text or JSON:

```sh
curl -N -X POST http://localhost:8080/chat/simple \
  -H "content-type: text/plain" \
  --data "Say hello in five words."
```

Implementation: `src/aimee/example/chat_server.clj`
