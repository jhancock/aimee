(ns aimee.example.control
  (:require [aimee.chat.client :as chat]
            [aimee.chat.sse :as chat-sse]
            [aimee.sse :as sse]
            [clojure.core.async :as async]))

(defn await-terminal-event!!
  "Wait for the terminal event (:complete or :error) from a channel.
  
  Returns the terminal event or nil if timeout occurs."
  [ch timeout-ms]
  (loop [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (let [remaining (- deadline (System/currentTimeMillis))]
      (when (pos? remaining)
        (let [[event port] (async/alts!! [ch (async/timeout remaining)])]
          (cond
            (not= port ch)
            nil

            (nil? event)
            nil

            (#{:complete :error} (:event event))
            event

            :else
            (recur deadline)))))))

(comment
  ;; ---------------------------------------------------------------------------
  ;; Control module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------
  ;;
  ;; This module demonstrates user-initiated stop and IO exception handling.
  ;; For :reason values on :complete events, see aimee.example.api.
  ;; Note: :error events do not include :reason - the exception is in :data.
  ;;
  ;; ---------------------------------------------------------------------------

  ;; Network helpers for API-backed examples
  (def openai-api-url (or (System/getenv "OPENAI_API_URL")
                          "https://api.openai.com/v1/chat/completions"))
  (def openai-api-key (System/getenv "OPENAI_API_KEY"))
  openai-api-key

  ;; Example 1: Stop a streaming request immediately
  ;; Call stop right away to intercept the stream before it completes.
  ;; Returns :complete with :reason :stopped and empty content.
  (def ch-stop-1 (async/chan 1))
  (def result-stop-1 (chat/start-request!
                      {:url openai-api-url
                       :api-key openai-api-key
                       :channel ch-stop-1
                       :model "gpt-5-mini"
                       :stream? true
                       :messages [{:role "user"
                                   :content "Write 2000 words about a library cat."}]}))
  ((:stop! result-stop-1))
  (def event-stop-1 (await-terminal-event!! ch-stop-1 10000))
  (:event event-stop-1)
  (:data event-stop-1)

  ;; Example 2: Stop after receiving some chunks
  ;; Read a few chunks, then stop. Accumulated content is preserved.
  (def ch-stop-2 (async/chan 64))
  (def result-stop-2 (chat/start-request!
                      {:url openai-api-url
                       :api-key openai-api-key
                       :channel ch-stop-2
                       :model "gpt-5-mini"
                       :stream? true
                       :messages [{:role "user"
                                   :content "Write 500 words about a library cat."}]}))
  (def chunk-1 (async/<!! ch-stop-2))
  (def chunk-2 (async/<!! ch-stop-2))
  (def chunk-3 (async/<!! ch-stop-2))
  (get-in chunk-2 [:data :parsed :content])
  ((:stop! result-stop-2))
  (def event-stop-2 (await-terminal-event!! ch-stop-2 10000))
  (:event event-stop-2)
  (:reason (:data event-stop-2))
  (:content (:data event-stop-2))

  ;; ---------------------------------------------------------------------------
  ;; IO exception during streaming (local, no network)
  ;; ---------------------------------------------------------------------------

  ;; Example 3: Simulated IO exception mid-stream
  ;; Create a custom InputStream that throws after reading ~n bytes.
  ;; This demonstrates :error event emission when stream read fails.
  (defn make-failing-stream
    "Create an InputStream that throws IOException after reading ~n bytes."
    [data fail-after]
    (let [bytes (.getBytes data "UTF-8")
          pos (atom 0)]
      (proxy [java.io.InputStream] []
        (read
         ([]
          (let [p @pos]
            (if (>= p (alength bytes))
              -1
              (if (>= p fail-after)
                (throw (java.io.IOException. "simulated connection loss"))
                (do
                  (swap! pos inc)
                  (aget bytes p))))))
         ([arg1]
          (if (instance? "[B" arg1)
            (let [buf arg1 p @pos]
              (if (>= p (alength bytes))
                -1
                (if (>= p fail-after)
                  (throw (java.io.IOException. "simulated connection loss"))
                  (do
                    (aset buf 0 (aget bytes p))
                    (swap! pos inc)
                    1))))
            (throw (UnsupportedOperationException. "read(int) not supported"))))
         ([buf off len]
          (let [p @pos]
            (if (>= p (alength bytes))
              -1
              (if (>= p fail-after)
                (throw (java.io.IOException. "simulated connection loss"))
                (do
                  (aset buf off (aget bytes p))
                  (swap! pos inc)
                  1)))))))))

  (def partial-sse
    (str "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"))
  (def failing-input (make-failing-stream partial-sse 50))
  (def error-events-3 (atom []))
  (def error-final-3 (atom nil))
  (def error-handlers-3
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! error-events-3 conj event))
      :complete! (fn [info] (reset! error-final-3 info))
      :error! (fn [ex] (swap! error-events-3 conj {:event :error :data ex}))
      :stream nil
      :on-parse-error :stop}))
  (sse/consume-sse!
   failing-input
   {:close-input? false
    :on-event (:on-event error-handlers-3)
    :on-complete (:on-complete error-handlers-3)
    :on-error (:on-error error-handlers-3)})
  (def error-event-3 (first (filter #(= :error (:event %)) @error-events-3)))
  (:event error-event-3)
  (ex-message (:data error-event-3))
  )
