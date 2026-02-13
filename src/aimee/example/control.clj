(ns aimee.example.control
  (:require [aimee.chat.client :as chat]
            [aimee.example.core :as core]
            [clojure.core.async :as async]))

(defn test-stop-streaming!
  "Example 8: Stop a streaming request and confirm :stopped (deterministic).

  Use a long prompt and wait for the terminal event after stop.
  All request options are defined inline for easy REPL inspection."
  []
  (let [ch (async/chan 1)
        request-opts {:url core/openai-api-url
                      :api-key core/openai-api-key
                      :channel ch
                      :model "gpt-4o-mini"
                      :stream? true
                      :messages [{:role "user"
                                  :content "Write 1200 words about a library cat."}]}
        result (chat/start-request! request-opts)]
    (Thread/sleep 100)
    ((:stop! result))
    (let [event-8 (core/await-terminal-event!! ch 10000)]
      {:terminal-event event-8})))

(comment
  ;; Run control tests

  ;; Stop streaming request
  (def result-8 (test-stop-streaming!))
  (:event (:terminal-event result-8))
  (:reason (:data (:terminal-event result-8))))
