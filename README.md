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

- `docs/aimee-streaming-spec.md`
- `docs/aimee-improvements.md`
- `docs/aimee-responses-spec.md`

## Build

```sh
clojure -T:build jar
```

## Publish to Clojars

```sh
clojure -T:build deploy
```

Requires Clojars credentials in `~/.clojars` or environment variables.
