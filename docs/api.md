# API Reference

## Entry point

`aimee.chat.client/start-request!`

```clojure
(start-request! opts)
```

Starts one chat completion request and returns:

```clojure
{:stop! (fn [])}
```

Calling `:stop!` requests termination and closes the active stream if present.

## Required options

- `:channel` caller-created `core.async` channel
- `:url` OpenAI-compatible chat completions endpoint
- `:api-key` bearer token
- `:model` model id string
- `:messages` non-empty sequence of chat messages

## Optional options

- `:stream?` default `false`
- `:parse-chunks?` default `true`
- `:accumulate?` default `true`
- `:on-parse-error` `:stop` (default) or `:continue`
- `:overflow-max` default `10000`
- `:overflow-mode` `:queue` (default) or `:block`
- `:channel-idle-timeout-ms` default `nil`
- `:http-timeout-ms` default `nil`
- `:headers` default `nil`
- `:include-usage?` default `false` (streaming requests)
- `:choices-n` fixed to `1` (validated)

## Event contract

Events are pushed to `:channel` as:

```clojure
{:event <keyword>
 :data  <payload>}
```

### `:chunk`

`(:data event)` is the raw parsed SSE event map:

```clojure
{:id "..."
 :type "..."
 :data "{...json...}"
 :parsed {...}} ; present when :parse-chunks? true
```

`(:parsed (:data event))` may include:

- `:content`
- `:role`
- `:tool-calls`
- `:function-call`
- `:finish-reason`
- `:usage`
- `:refusal`
- `:refusal?`
- `:done?`
- `:skip?`

### `:complete`

`(:data event)` includes at least:

```clojure
{:content "..."}
```

It may also include:

- `:finish-reason`
- `:role`
- `:tool-calls`
- `:function-call`
- `:usage`
- `:refusal`
- `:refusal?`
- `:done-event`
- `:reason` (`:stopped`, `:timeout`, `:eof`, `:error`)

### `:error`

`(:data event)` is an exception (`ex-info` for structured cases).

## Streaming behavior notes

- `:parse-chunks? false` keeps chunk payloads raw (no `:parsed`).
- `:accumulate? false` disables content accumulation in `:complete`.
- `:on-parse-error :stop` emits `:error` and closes.
- `:on-parse-error :continue` logs and skips malformed chunks.

## Backpressure behavior

- `:overflow-mode :queue` lazily creates a bounded overflow queue (`:overflow-max`).
- `:overflow-mode :block` immediately blocks producer writes on full channel.

## Idle timeout behavior

When `:channel-idle-timeout-ms` is set, timeout checks run periodically. If no progress is emitted to the channel within the window:

- a `:complete` event is emitted with `{:reason :timeout}`
- the stream is stopped/closed

## Non-streaming behavior

For `:stream? false`, a single HTTP response is parsed and emitted as `:complete`, or `:error` for non-2xx.

## Helper namespace

`aimee.sse-helpers`

- `format-sse-data`
- `format-sse-done`
- `event->simplified-sse`

These are utility functions for adapting channel events to browser-friendly SSE output.
