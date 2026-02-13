(ns aimee.example.streaming
  (:require [aimee.chat.client :as chat]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defn log-events!!
  "Blocking REPL helper that logs each stream event and returns terminal event.

  Event handling:
  - :chunk -> log and continue
  - :complete -> log and return terminal event
  - :error -> log and return terminal event"
  [ch]
  (loop []
    (if-let [event (async/<!! ch)]
      (cond
        (= :chunk (:event event))
        (do
          (log/info "stream chunk event" event)
          (recur))

        (= :complete (:event event))
        (do
          (log/info "stream complete event" event)
          event)

        (= :error (:event event))
        (do
          (log/error "stream error event" event)
          event)

        :else
        (do
          (log/warn "stream unknown event" event)
          (recur)))
      (do
        (log/info "stream channel closed")
        nil))))

(comment
  ;; ---------------------------------------------------------------------------
  ;; Streaming module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------

  ;; Network helpers for API-backed examples
  (def openai-api-url (or (System/getenv "OPENAI_API_URL")
                          "https://api.openai.com/v1/chat/completions"))
  (def openai-api-key (System/getenv "OPENAI_API_KEY"))
  openai-api-key
  ;; Optional local override:
  ;; (def openai-api-url "https://your-provider.example/v1/chat/completions")
  ;; (def openai-api-key "sk-...")

  ;; Auth note:
  ;; Set auth using one of:
  ;; - :api-key
  ;; - :api-key-fn (fn [_opts] "...")
  ;; - :headers {"Authorization" "Bearer ..."}

  ;; Example 1: Standard streaming (default parse + accumulate)
  (def ch-1 (async/chan 64))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-1
    :model "gpt-4o-mini"
    :stream? true
    :messages [{:role "user" :content "Say hello in exactly two sentences."}]})
  ;; Logs each event as it arrives.
  (def terminal-1 (log-events!! ch-1))
  (:event terminal-1)
  (get-in terminal-1 [:data :content])

  ;; Example 2: Streaming with :include-usage? true
  (def ch-2 (async/chan 64))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-2
    :model "gpt-4o-mini"
    :stream? true
    :include-usage? true
    :messages [{:role "user" :content "Count to 3."}]})
  (def terminal-2 (log-events!! ch-2))
  (:event terminal-2)
  (get-in terminal-2 [:data :usage])

  ;; Example 3: Raw SSE proxy mode (:parse-chunks? false, :accumulate? false)
  (def ch-3 (async/chan 64))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-3
    :model "gpt-4o-mini"
    :stream? true
    :parse-chunks? false
    :accumulate? false
    :messages [{:role "user" :content "Quick greeting."}]})
  (def terminal-3 (log-events!! ch-3))
  (:event terminal-3)
  (get-in terminal-3 [:data :content])

  ;; Example 4: Parsed chunks without accumulation (:accumulate? false)
  (def ch-4 (async/chan 64))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-4
    :model "gpt-4o-mini"
    :stream? true
    :accumulate? false
    :messages [{:role "user" :content "Count from 1 to 3."}]})
  (def terminal-4 (log-events!! ch-4))
  (:event terminal-4)
  (get-in terminal-4 [:data :content])
  )
