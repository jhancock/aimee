# aimee

Aimee is a library for streaming (SSE) and non-streaming OpenAI compatible Chat Completions over core.async channels. Aimee is intended to be highly robust and scalable. Depends on org.clojure/core.async 1.9.829-alpha2 to leverage latest JDK 21+ virtual thread behavior. Tested with OpenAI Chat Conpletion API.

## Install

```edn
{:deps
 {net.clojars.jhancock/aimee {:mvn/version "0.2.0"}}}
```

## How It Works

1. The caller creates a `core.async` channel
2. Pass the channel to `start-request!` with your request options. This creates a virtual thread to handle the HTTP request and result processing lifecycle.
3. Events are written to the channel as the request progresses. Events are a map {:event <event-type> :data <data-map>}. Events types are :chunk, :complete and :error. A :chunk event is an SSE Chat Completion "chunk". At the end of any stream or non-stream request there will be a :complete event except in the case of :error.
4. Consume events from your channel (via `go-loop`, `<!!`, etc.)
5. Channel closes after `:complete` or `:error`

The channel is yours. You control its buffer size, how you consume from it. The library handles the chat completion request lifecycle, writes events to the channel, closes it when done and handles slow channel consumer overflow with backpressure options. Aimee provides helper functions such as `aimee.chat.ring/->ring-stream` to write SSE chunks to an HTTP streaming response. 

See [`aimee.example.chat-server`](src/aimee/example/chat_server.clj) for a complete working example. This example uses the latest Jetty 12.x, virtual threads, SSE streaming response.

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
        (prn "Chunk Event" event)
        (recur))

      :complete
      (prn "Complete Event" event)

      :error
      (prn "Error Event" event))))
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
- **`:channel-idle-timeout-ms`** — `nil` — Abort if no progress for this duration. `nil` means aimee doesn't impose a timeout on channel consumption.
- **`:http-timeout-ms`** — `nil` — HTTP request timeout. `nil` means the HTTP library uses its default timeout.
- **`:include-usage?`** — `false` — Include usage stats in streaming `:complete`
- **`:on-parse-error`** — `:stop` — `:stop` emits error and closes; `:continue` logs and skips

For full defaults and descriptions, see [`aimee.chat.options/defaults`](src/aimee/chat/options.clj).

## Events

All events have shape `{:event <keyword> :data <payload>}`.

### `:chunk`

Streaming content delta. Emitted for each SSE data chunk during streaming.

```clojure
;; example chunk event
{:event :chunk
 :data {:id nil
        :type nil
        :data "{\"id\":\"chatcmpl-...\",\"object\":\"chat.completion.chunk\",...}"
        :parsed {:content "Hello"
                 :role "assistant"
                 :tool-calls nil
                 :function-call nil
                 :api-finish-reason nil
                 :usage nil
                 :done? false}}}
```

**`:data` map:**
- **`:id`** — SSE event ID (often nil)
- **`:type`** — SSE event type (often nil)
- **`:data`** — Raw JSON string from API
- **`:parsed`** — Parsed OpenAI chunk data

**`:parsed` map:**
- **`:content`** — Delta text (may be empty string)
- **`:role`** — Role string (appears in first chunk, nil thereafter)
- **`:tool-calls`** — Tool definitions (when present)
- **`:function-call`** — Function call (deprecated format, when present)
- **`:api-finish-reason`** — `"stop"`, `"length"`, `"content_filter"`, `"tool_calls"` (in final chunk)
- **`:usage`** — Token counts (when `:include-usage? true`, in final chunk)
- **`:done?`** — `true` when `[DONE]` sentinel received

### `:complete`

Terminal success event. Emitted once when request completes.

```clojure
;; example complete event
{:event :complete
 :data {:content "Hello! How can I assist you today?"
        :reason :done
        :api-finish-reason "stop"
        :role "assistant"
        :done-event {:id nil :type nil :data "[DONE]"}}}
```

**`:data` map:**
- **`:content`** — Accumulated text (when `:accumulate? true`, empty string otherwise)
- **`:reason`** — Library completion reason: `:done`, `:stopped`, `:timeout`, `:eof`
- **`:api-finish-reason`** — Passthrough from API: `"stop"`, `"length"`, `"content_filter"`, `"tool_calls"`
- **`:role`** — Final role (usually `"assistant"`)
- **`:tool-calls`** — Accumulated tool calls (when present)
- **`:usage`** — Token counts (when available)
- **`:refusal`** — Refusal content (when present)
- **`:refusal?`** — `true` if response was a refusal
- **`:done-event`** — The `[DONE]` SSE event that terminated the stream

### `:error`

Terminal failure event. Emitted once when request fails.

```clojure
;; example error event
{:event :error
 :data #error {:cause "HTTP error"
               :data {:status 401
                      :body "{\"error\":{\"message\":\"Incorrect API key...\"}}"}}}
```

**`:data`** — Exception with:
- **`:cause`** — Error message
- **`:data`** — Exception data map (may include `:status`, `:body` for HTTP errors)

## Backpressure options

When your channel consumer is slower than the SSE stream, events back up. Two strategies handle this:

### `:backpressure :queue` (default)

Events first attempt direct write to your channel. When full, creates a bounded overflow queue (capacity set by `:queue-capacity`) that drains in background thread.

**Tradeoff:** Extra memory for queue, but preserves all events and keeps HTTP stream flowing.

**Use when:** Consumer is temporarily slow but will catch up.

### `:backpressure :block`

Writes directly block when channel is full. No overflow queue.

**Tradeoff:** Simpler, but blocks the SSE stream thread. Slow consumer stalls the entire HTTP response.

**Use when:** Consumer is always fast, or you want backpressure to slow the HTTP response.

### Channel Buffer

Your channel buffer is the first line of defense. Larger buffers absorb temporary slowdowns before backpressure engages:

```clojure
(async/chan 10)   ;; Small - backpressure kicks in quickly if your channel consumer is slow
(async/chan 1000) ;; Large - absorbs bursts. However, the 
```

### Idle Timeout

`:channel-idle-timeout-ms` detects stalled consumers. Emits `:complete` with `:reason :timeout` if no event is successfully delivered to your consumer for this duration. Progress tracks only successful channel writes (not queue additions).

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

## Examples

**[src/aimee/example/](src/aimee/example/)** — REPL examples for streaming, parsing, backpressure, lifecycle


## Example Chat Server

HTTP example app that serves a one-page chat client at `/chat`.

### Requirements

- Environment variable `OPENAI_API_KEY` must be set or modify the example code to set this value
- `OPENAI_API_URL` optionally sets the API URL (defaults to OpenAI's)
- Model default is `gpt-5-mini`

### Run

```sh
clojure -M:dev
```
`aimee.example.chat-server` has a comment block at the end to start and stop the server

Then open http://localhost:8080/chat
or test with curl
```sh
curl -X POST http://localhost:8080/chat -H 'Content-Type: application/json' -d '{\"messages\":[{\"role\":\"user\",\"text\":\"hello\"}]}'
```

### Deploy (author notes)

0. Update version in build.clj and README.md
1. Create a deploy token in Clojars: https://clojars.org/tokens
2. In your terminal:

  export CLOJARS_USERNAME=jhancock
  export CLOJARS_PASSWORD='YOUR_DEPLOY_TOKEN'

3. Publish:

  clojure -T:build deploy

On success, commit and tag:

  git commit -m "Release 0.2.0"
  git tag -a v0.2.0 -m "Release v0.2.0"
  git push origin "$(git branch --show-current)"
  git push origin v0.2.0