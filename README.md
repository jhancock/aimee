# aimee

Core.async-first streaming client for OpenAI-compatible chat completions.

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
              :model "gpt-4o-mini"
              :stream? true
              :messages [{:role "user" :content "Hello!"}]}))

(async/go-loop []
  (when-let [event (async/<! ch)]
    (case (:event event)
      :chunk (println "Chunk" (get-in event [:data :parsed :content]))
      :complete (println "Done" (:data event))
      :error (println "Error" (:data event)))
    (recur))

((:stop! result))
```

## Docs

- **[docs/api.md](docs/api.md)** — Options reference, event contract
- **[docs/architecture.md](docs/architecture.md)** — Design principles, runtime flow
- **[src/aimee/example/](src/aimee/example/)** — REPL examples

## Build

```sh
clojure -T:build jar
```

## Publish

```sh
clojure -T:build deploy
```

## Validation

REPL-first via `(comment ...)` blocks in `src/aimee/example/`.

## Example Chat Server

Bare-bones HTTP example app that serves a one-page Deep Chat client at `/chat` and streams responses through `aimee.chat.client`.

### Requirements

- `OPENAI_API_KEY` must be set
- `OPENAI_API_URL` is optional (defaults to `https://api.openai.com/v1/chat/completions`)
- Model default is `gpt-4o-mini`

### Run

```sh
clojure -M:chat-server
```

Then open:

```text
http://localhost:8080/chat
```

Optional custom port:

```sh
clojure -M:chat-server -- 8090
```

### Simple SSE Test Endpoint (No Deep Chat Client)

Use `POST /chat/simple` to test streaming with a simple input string.

Send plain text:

```sh
curl -N -X POST http://localhost:8080/chat/simple \
  -H "content-type: text/plain" \
  --data "Say hello in five words."
```

Or send JSON:

```sh
curl -N -X POST http://localhost:8080/chat/simple \
  -H "content-type: application/json" \
  --data '{"input":"Say hello in five words."}'
```

The endpoint streams SSE frames and ends with `data: [DONE]`.

### Verify Streaming In Browser

Open:

```text
http://localhost:8080/chat
```

Send a prompt and watch the assistant response stream in.

Implementation lives in `src/aimee/example/chat_server.clj`.
