# aimee

Core.async-first streaming client for LLM APIs.

## Install

Add to your deps.edn:

```edn
{jhancock/aimee {:mvn/version "0.1.0-SNAPSHOT"}}
```

## Usage

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
      :chunk (println "Chunk" (:content (:parsed (:data event))))
      :complete (println "Done" (:data event))
      :error (println "Error" (:data event)))
    (recur)))

((:stop! result))
```

## Docs

- `docs/overview.md`
- `docs/api.md`
- `docs/architecture.md`

## Validation

There is intentionally no standalone automated test command yet.

Validation is REPL-first via executable `(comment ...)` blocks in:

- `src/aimee/simulator.clj`
- `src/aimee/stress.clj`
- `src/aimee/scheduler_simulator.clj`

## Build

```sh
clojure -T:build jar
```

## Publish to Clojars

```sh
clojure -T:build deploy
```

Requires Clojars credentials in `~/.clojars` or environment variables.
