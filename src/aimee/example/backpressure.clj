(ns aimee.example.backpressure
  (:require [aimee.chat.client :as chat]
            [aimee.chat.emitter :as emitter]
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

(defn- make-sse-sample
  "Create an SSE sample with n chunk events and a [DONE] sentinel."
  [n]
  (str (apply str (repeat n "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"))
       "data: [DONE]\n\n"))

(comment
  ;; ---------------------------------------------------------------------------
  ;; Backpressure module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------
  ;;
  ;; Demonstrates overflow handling (:queue vs :block) and idle-timeout behavior.
  ;; Examples 1-2 are local (no network). Examples 3-6 require OPENAI_API_KEY.
  ;; For :reason values on :complete events, see aimee.example.api.
  ;;
  ;; ---------------------------------------------------------------------------

  ;; Network helpers for API-backed examples
  (def openai-api-url (or (System/getenv "OPENAI_API_URL")
                          "https://api.openai.com/v1/chat/completions"))
  (def openai-api-key (System/getenv "OPENAI_API_KEY"))
  openai-api-key

  ;; Helper: SSE sample data generator
  ;; Create an SSE sample with n chunk events and a [DONE] sentinel.
  (def sample-5 (make-sse-sample 5))
  sample-5

  ;; ---------------------------------------------------------------------------
  ;; Local SSE backpressure tests (no network required)
  ;; ---------------------------------------------------------------------------

  ;; Example 1: Overflow queue mode (:overflow-mode :queue)
  ;; Producer fills faster than consumer, queue buffers events.
  (def sse-data-1 (make-sse-sample 300))
  (def input-1 (java.io.ByteArrayInputStream. (.getBytes sse-data-1 "UTF-8")))
  (def ch-1 (async/chan 1))
  (def terminated-1? (atom false))
  (def callbacks-1 (emitter/make-channel-callbacks ch-1 {:overflow-max 50
                                                          :overflow-mode :queue
                                                          :terminated? terminated-1?}))
  (def handlers-1 (chat-sse/make-stream-handlers
                   {:emit! (:emit! callbacks-1)
                    :complete! (:complete! callbacks-1)
                    :error! (:error! callbacks-1)}))
  (def consumed-1 (atom 0))
  (def consumer-1
    (async/thread
      (loop []
        (when-let [_event (async/<!! ch-1)]
          (swap! consumed-1 inc)
          (Thread/sleep 5)
          (recur)))))
  (def producer-1
    (async/thread
      (sse/consume-sse!
       input-1
       {:on-event (:on-event handlers-1)
        :on-complete (:on-complete handlers-1)
        :on-error (:on-error handlers-1)})))
  (Thread/sleep 1000)
  @consumed-1
  @terminated-1?

  ;; Example 2: Block mode immediate backpressure (:overflow-mode :block)
  ;; No queue, producer blocks when channel full.
  (def sse-data-2 (make-sse-sample 150))
  (def input-2 (java.io.ByteArrayInputStream. (.getBytes sse-data-2 "UTF-8")))
  (def ch-2 (async/chan 1))
  (def terminated-2? (atom false))
  (def callbacks-2 (emitter/make-channel-callbacks ch-2 {:overflow-max 50
                                                          :overflow-mode :block
                                                          :terminated? terminated-2?}))
  (def handlers-2 (chat-sse/make-stream-handlers
                   {:emit! (:emit! callbacks-2)
                    :complete! (:complete! callbacks-2)
                    :error! (:error! callbacks-2)}))
  (def consumed-2 (atom 0))
  (def consumer-2
    (async/thread
      (loop []
        (when-let [_event (async/<!! ch-2)]
          (swap! consumed-2 inc)
          (Thread/sleep 5)
          (recur)))))
  (def producer-2
    (async/thread
      (sse/consume-sse!
       input-2
       {:on-event (:on-event handlers-2)
        :on-complete (:on-complete handlers-2)
        :on-error (:on-error handlers-2)})))
  (Thread/sleep 1000)
  @consumed-2
  @terminated-2?

  ;; ---------------------------------------------------------------------------
  ;; Idle-timeout tests (require OPENAI_API_KEY and network)
  ;; ---------------------------------------------------------------------------

  ;; Example 3: Idle-timeout after initial progress
  ;; Read one early chunk, then stop consuming to force channel stall and timeout.
  (def ch-3 (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-3
    :model "gpt-4o-mini"
    :stream? true
    :channel-idle-timeout-ms 1500
    :messages [{:role "user"
                :content "Write 1200 words about a library cat."}]})
  (def first-3 (async/<!! ch-3))
  (:event first-3)
  (def event-3
    (if (#{:complete :error} (:event first-3))
      first-3
      (do
        (Thread/sleep 2500)
        (await-terminal-event!! ch-3 10000))))
  event-3
  (:data event-3)
  (:reason (:data event-3))

  ;; Example 4: Idle-timeout without consuming
  ;; Use an unbuffered channel so no consumer means zero progress from the start.
  ;; After timeout elapses, first terminal read should be {:reason :timeout}.
  (def ch-4 (async/chan))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-4
    :model "gpt-4o-mini"
    :stream? true
    :channel-idle-timeout-ms 1500
    :messages [{:role "user"
                :content "Write 1200 words about a library cat."}]})
  (Thread/sleep 2500)
  (def event-4 (await-terminal-event!! ch-4 10000))
  event-4
  (:data event-4)
  (:reason (:data event-4))

  ;; Example 5: Force idle-timeout by blocking the consumer
  ;; The channel fills immediately, so no progress is recorded and timeout fires.
  (def ch-5 (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-5
    :model "gpt-4o-mini"
    :stream? true
    :channel-idle-timeout-ms 200
    :messages [{:role "user"
                :content "Write 1000 words about a library cat."}]})
  (Thread/sleep 1000)
  (def event-5 (async/<!! ch-5))
  event-5
  (:data event-5)
  (:reason (:data event-5))

  ;; Example 6: No-timeout contrast (:channel-idle-timeout-ms nil)
  ;; Even with delayed consumption, terminal event should be normal completion, not :timeout.
  (def ch-6 (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-6
    :model "gpt-4o-mini"
    :stream? true
    :channel-idle-timeout-ms nil
    :messages [{:role "user"
                :content "Write 600 words about a library cat."}]})
  (Thread/sleep 2000)
  (def event-6 (await-terminal-event!! ch-6 30000))
  event-6
  (:data event-6)
  (:reason (:data event-6))
  )
