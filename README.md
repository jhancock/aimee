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
