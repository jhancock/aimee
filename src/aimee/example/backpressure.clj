(ns aimee.example.backpressure
  (:require [aimee.chat.client :as chat]
            [aimee.chat.emitter :as emitter]
            [aimee.chat.parser :as parser]
            [aimee.chat.sse :as chat-sse]
            [aimee.example.core :as core]
            [aimee.sse :as sse]
            [clojure.core.async :as async]))

(defn- make-sse-sample
  "Create an SSE sample with n chunk events and a [DONE] sentinel."
  [n]
  (str (apply str (repeat n "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"))
       "data: [DONE]\n\n"))

(defn- collect-until-terminal!!
  [ch max-wait-ms]
  (loop [events []
         deadline (+ (System/currentTimeMillis) max-wait-ms)]
    (let [remaining (- deadline (System/currentTimeMillis))]
      (if (pos? remaining)
        (let [[event port] (async/alts!! [ch (async/timeout remaining)])]
          (cond
            (not= port ch)
            {:events events :timeout? true}

            (nil? event)
            {:events events :closed? true}

            :else
            (let [events' (conj events event)]
              (if (#{:complete :error} (:event event))
                {:events events' :terminal event}
                (recur events' deadline)))))
        {:events events :timeout? true}))))

(defn- wait-until-terminated!!
  [run timeout-ms]
  (let [terminated? (:terminated run)
        consumed (:consumed run)
        consumer (:consumer run)
        [event port] (if consumer
                       (async/alts!! [consumer (async/timeout timeout-ms)])
                       [nil nil])]
    {:terminated? (if terminated? @terminated? false)
     :consumed (if consumed @consumed 0)
     :consumer-finished? (and consumer (= port consumer))
     :timeout? (and consumer (not= port consumer))
     :consumer-result event}))

(defn test-overflow-queue-mode!
  "Example 5f: Overflow queue mode (:overflow-mode :queue).

  Producer fills faster than consumer, queue buffers events."
  []
  (let [chunks 300
        overflow-max 50
        buffer-size 1
        consumer-delay-ms 5
        sse-data (make-sse-sample chunks)
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        ch (async/chan buffer-size)
        terminated? (atom false)
        callbacks (emitter/make-channel-callbacks ch {:overflow-max overflow-max
                                                      :overflow-mode :queue
                                                      :terminated? terminated?})
        handlers (chat-sse/make-stream-handlers
                  {:emit! (:emit! callbacks)
                   :complete! (:complete! callbacks)
                   :error! (:error! callbacks)})
        consumed (atom 0)
        consumer (async/thread
                   (loop []
                     (when-let [_event (async/<!! ch)]
                       (swap! consumed inc)
                       (Thread/sleep consumer-delay-ms)
                       (recur))))
        producer (async/thread
                   (sse/consume-sse!
                    input
                    {:on-event (:on-event handlers)
                     :on-complete (:on-complete handlers)
                     :on-error (:on-error handlers)}))]
    (Thread/sleep 1000)
    {:consumed @consumed
     :terminated? @terminated?
     :run {:channel ch
           :consumed consumed
           :terminated terminated?
           :producer producer
           :consumer consumer}}))

(defn test-block-mode!
  "Example 5f (alt): Block mode immediate backpressure (:overflow-mode :block).

  Immediate backpressure, no queue, producer blocks when channel full."
  []
  (let [chunks 150
        overflow-mode :block
        buffer-size 1
        consumer-delay-ms 5
        sse-data (make-sse-sample chunks)
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        ch (async/chan buffer-size)
        terminated? (atom false)
        callbacks (emitter/make-channel-callbacks ch {:overflow-max 50
                                                      :overflow-mode overflow-mode
                                                      :terminated? terminated?})
        handlers (chat-sse/make-stream-handlers
                  {:emit! (:emit! callbacks)
                   :complete! (:complete! callbacks)
                   :error! (:error! callbacks)})
        consumed (atom 0)
        consumer (async/thread
                   (loop []
                     (when-let [_event (async/<!! ch)]
                       (swap! consumed inc)
                       (Thread/sleep consumer-delay-ms)
                       (recur))))
        producer (async/thread
                   (sse/consume-sse!
                    input
                    {:on-event (:on-event handlers)
                     :on-complete (:on-complete handlers)
                     :on-error (:on-error handlers)}))]
    (Thread/sleep 1000)
    {:consumed @consumed
     :terminated? @terminated?
     :run {:channel ch
           :consumed consumed
           :terminated terminated?
           :producer producer
           :consumer consumer}}))

(defn test-idle-timeout-with-progress!
  "Example 7: Idle-timeout after initial progress (deterministic timeout).

  Read one early chunk, then stop consuming to force channel stall and timeout."
  []
  (let [ch (async/chan 1)
        request-opts {:url core/openai-api-url
                      :api-key core/openai-api-key
                      :channel ch
                      :model "gpt-4o-mini"
                      :stream? true
                      :channel-idle-timeout-ms 1500
                      :messages [{:role "user"
                                  :content "Write 1200 words about a library cat."}]}]
    (chat/start-request! request-opts)
    (let [first-7 (async/<!! ch)
          event-7 (if (#{:complete :error} (:event first-7))
                    first-7
                    (do
                      (Thread/sleep 2500)
                      (core/await-terminal-event!! ch 10000)))]
      {:first-event first-7
       :terminal-event event-7})))

(defn test-idle-timeout-without-consuming!
  "Example 7b: Idle-timeout without consuming (confirm terminal :timeout).

  Use an unbuffered channel so no consumer means zero progress from the start.
  After timeout elapses, first terminal read should be {:reason :timeout}."
  []
  (let [ch (async/chan)
        request-opts {:url core/openai-api-url
                      :api-key core/openai-api-key
                      :channel ch
                      :model "gpt-4o-mini"
                      :stream? true
                      :channel-idle-timeout-ms 1500
                      :messages [{:role "user"
                                  :content "Write 1200 words about a library cat."}]}]
    (chat/start-request! request-opts)
    (Thread/sleep 2500)
    (let [event-7b (core/await-terminal-event!! ch 10000)]
      {:terminal-event event-7b})))

(defn test-force-idle-timeout!
  "Example 9: Force idle-timeout by blocking the consumer.

  The channel fills immediately, so no progress is recorded and timeout fires."
  []
  (let [ch (async/chan 1)
        request-opts {:url core/openai-api-url
                      :api-key core/openai-api-key
                      :channel ch
                      :model "gpt-4o-mini"
                      :stream? true
                      :channel-idle-timeout-ms 200
                      :messages [{:role "user"
                                  :content "Write 1000 words about a library cat."}]}]
    (chat/start-request! request-opts)
    (Thread/sleep 1000)
    (let [event-9 (async/<!! ch)]
      {:terminal-event event-9})))

(defn test-no-timeout-contrast!
  "Example 9b: No-timeout contrast (:channel-idle-timeout-ms nil).

  Even with delayed consumption, terminal event should be normal completion, not :timeout."
  []
  (let [ch (async/chan 1)
        request-opts {:url core/openai-api-url
                      :api-key core/openai-api-key
                      :channel ch
                      :model "gpt-4o-mini"
                      :stream? true
                      :channel-idle-timeout-ms nil
                      :messages [{:role "user"
                                  :content "Write 600 words about a library cat."}]}]
    (chat/start-request! request-opts)
    (Thread/sleep 2000)
    (let [event-9b (core/await-terminal-event!! ch 30000)]
      {:terminal-event event-9b})))

(comment
  ;; Run backpressure tests

  ;; Overflow queue mode
  (def overflow-queue (test-overflow-queue-mode!))
  (:consumed overflow-queue)

  ;; Block mode
  (def overflow-block (test-block-mode!))
  (:consumed overflow-block)

  ;; Idle-timeout with progress
  (def result-7 (test-idle-timeout-with-progress!))
  (:reason (:data (:terminal-event result-7)))

  ;; Idle-timeout without consuming
  (def result-7b (test-idle-timeout-without-consuming!))
  (:reason (:data (:terminal-event result-7b)))

  ;; Force idle-timeout
  (def result-9 (test-force-idle-timeout!))
  (:reason (:data (:terminal-event result-9)))

  ;; No-timeout contrast
  (def result-9b (test-no-timeout-contrast!))
  (:reason (:data (:terminal-event result-9b))))
