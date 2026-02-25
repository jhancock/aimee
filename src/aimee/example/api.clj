(ns aimee.example.api
  (:require [aimee.chat.client :as chat]
            [clojure.core.async :as async]))

(comment
  ;; ---------------------------------------------------------------------------
  ;; API module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------
  ;;
  ;; TERMINAL EVENT :reason VALUES
  ;; ------------------------------
  ;; Every :complete event includes a :reason key indicating how it terminated:
  ;;
  ;;   :done     - Normal completion (stream finished with [DONE] or non-streaming)
  ;;   :stopped  - User called stop! to cancel the request
  ;;   :timeout  - Channel idle timeout exceeded (no consumer progress)
  ;;   :eof      - Stream ended unexpectedly without [DONE] sentinel
  ;;
  ;; :error events do not include :reason - the exception in :data describes the failure.
  ;;
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

  ;; Example 1: Non-streaming call using :api-key
  (def ch-1 (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-1
    :model "gpt-5-mini"
    :stream? false
    :messages [{:role "user" :content "Count to 5."}]})
  (def event-1 (async/<!! ch-1)) 
  event-1
  (:event event-1)
  (get-in event-1 [:data :content])

  ;; Example 2: Non-streaming call using :api-key-fn
  (def ch-2 (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key-fn (fn [_opts] openai-api-key)
    :channel ch-2
    :model "gpt-5-mini"
    :stream? false
    :messages [{:role "user" :content "Count to 5 backwards."}]})
  (def event-2 (async/<!! ch-2))
  event-2
  (:event event-2)
  (get-in event-2 [:data :content])

  ;; Example 3: HTTP error flow (non-2xx response)
  (def ch-3 (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key "invalid-key"
    :channel ch-3
    :model "gpt-5-mini"
    :stream? false
    :messages [{:role "user" :content "This will not succeed."}]})
  (def event-3 (async/<!! ch-3))
  event-3
  (:event event-3)
  (ex-message (:data event-3))
  (ex-data (:data event-3))
  )
