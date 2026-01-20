(ns aimee.stress
  (:require [aimee.chat.client :as chat]
            [aimee.chat.emitter :as emitter]
            [aimee.chat.parser :as parser]
            [aimee.chat.sse :as chat-sse]
            [aimee.sse :as sse]
            [clojure.core.async :as async]
            [parker.config :as config]))

(defn make-sse-sample
  "Create an SSE sample with n chunk events and a [DONE] sentinel."
  [n]
  (str (apply str
              (repeat n "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"))
       "data: [DONE]\n\n"))

(defn run-overflow-test!
  "Simulate overflow handling using a local SSE stream and a slow consumer.

  Options:
  - :chunks (default 2000)
  - :overflow-max (default 100)
  - :overflow-mode (default :queue, use :block for immediate backpressure)
  - :buffer-size (default 1)
  - :consumer-delay-ms (default 10)
  "
  [{:keys [chunks overflow-max overflow-mode buffer-size consumer-delay-ms]
    :or {chunks 2000 overflow-max 100 overflow-mode :queue buffer-size 1 consumer-delay-ms 10}}]
  (let [sse-data (make-sse-sample chunks)
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        ch (async/chan buffer-size)
        terminated? (atom false)
        callbacks (emitter/make-channel-callbacks ch {:overflow-max overflow-max
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
    {:channel ch
     :consumed consumed
     :terminated terminated?
     :producer producer
     :consumer consumer}))

(defn run-idle-timeout-test!
  "Simulate idle-timeout by starting a streaming request and not consuming it.

  Options:
  - :channel-idle-timeout-ms (default 1500)
  - :buffer-size (default 1)
  - :max-wait-ms (default 5000)
  - :message (default long prompt to trigger multiple chunks)
  "
  [{:keys [channel-idle-timeout-ms buffer-size max-wait-ms message]
    :or {channel-idle-timeout-ms 1500
         buffer-size 1
         max-wait-ms 5000
         message "Write 200 words about a small cat exploring a library."}}]
  (let [ch (async/chan buffer-size)
        result (chat/start-request!
                {:url (config/openai-url)
                 :api-key (config/openai-key)
                 :channel ch
                 :model "gpt-4o-mini"
                 :stream? true
                 :channel-idle-timeout-ms channel-idle-timeout-ms
                 :messages [{:role "user" :content message}]})]
    (Thread/sleep (* 2 channel-idle-timeout-ms))
    (loop [deadline (+ (System/currentTimeMillis) max-wait-ms)
           last-event nil]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (pos? remaining)
          (let [[event _] (async/alts!! [ch (async/timeout remaining)])]
            (cond
              (nil? event) {:result result :event last-event}
              (= :complete (:event event)) {:result result :event event}
              :else (recur deadline event)))
          {:result result :event last-event})))))

(defn run-parse-chunks-test!
  "Test :parse-chunks? option with local SSE stream.

  Options:
  - :parse-chunks? (default true) - When true, chunks include :parsed key
  - :chunks (default 10)
  "
  [{:keys [parse-chunks? chunks overflow-max overflow-mode]
    :or {parse-chunks? true chunks 10 overflow-max 100 overflow-mode :queue}}]
  (let [sse-data (make-sse-sample chunks)
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        ch (async/chan 1)
        terminated? (atom false)
        callbacks (emitter/make-channel-callbacks ch {:overflow-max overflow-max
                                                      :overflow-mode overflow-mode
                                                      :terminated? terminated?})
        handlers (chat-sse/make-stream-handlers
                  {:emit! (:emit! callbacks)
                   :complete! (:complete! callbacks)
                   :error! (:error! callbacks)
                   :parse-chunks? parse-chunks?})]
    (async/thread
      (sse/consume-sse!
       input
       {:on-event (:on-event handlers)
        :on-complete (:on-complete handlers)
        :on-error (:on-error handlers)}))
    ;; Collect all events
    (Thread/sleep 100)
    (loop [acc []]
      (let [e (async/poll! ch)]
        (if e
          (recur (conj acc e))
          (let [chunk (first (filter #(= :chunk (:event %)) acc))]
            {:parse-chunks? parse-chunks?
             :has-parsed? (contains? (:data chunk) :parsed)
             :parsed (:parsed (:data chunk))
             :event-count (count acc)}))))))

(defn run-accumulate-test!
  "Test :accumulate? option with local SSE stream.

  Options:
  - :accumulate? (default true) - When true, builds content
  - :chunks (default 10)
  "
  [{:keys [accumulate? chunks overflow-max overflow-mode]
    :or {accumulate? true chunks 10 overflow-max 100 overflow-mode :queue}}]
  (let [sse-data (make-sse-sample chunks)
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        ch (async/chan 10)
        terminated? (atom false)
        callbacks (emitter/make-channel-callbacks ch {:overflow-max overflow-max
                                                      :overflow-mode overflow-mode
                                                      :terminated? terminated?})
        handlers (chat-sse/make-stream-handlers
                  {:emit! (:emit! callbacks)
                   :complete! (:complete! callbacks)
                   :error! (:error! callbacks)})
        accumulator (when accumulate? parser/accumulate-content)]
    (async/thread
      (sse/consume-sse!
       input
       {:accumulator accumulator
        :initial-acc ""
        :terminated? terminated?
        :on-event (:on-event handlers)
        :on-complete (:on-complete handlers)
        :on-error (:on-error handlers)}))
    ;; Wait for completion and collect result
    (Thread/sleep 500)
    (let [events (loop [acc []]
                   (let [e (async/poll! ch)]
                     (if e
                       (recur (conj acc e))
                       acc)))]
      {:accumulate? accumulate?
       :event-count (count events)
       :content (when-let [complete (first (filter #(= :complete (:event %)) events))]
                  (:content (:data complete)))})))

(comment
  ;; Overflow stress test (local SSE stream, no network)
  (def overflow-run
    (run-overflow-test! {:chunks 2000 :overflow-max 100 :buffer-size 1 :consumer-delay-ms 10}))
  @(:consumed overflow-run)

  ;; Parse-chunks test (local SSE stream, no network)
  (run-parse-chunks-test! {:parse-chunks? true :chunks 5})
  (run-parse-chunks-test! {:parse-chunks? false :chunks 5})

  ;; Accumulate test (local SSE stream, no network)
  (run-accumulate-test! {:accumulate? true :chunks 5})
  (run-accumulate-test! {:accumulate? false :chunks 5})

  ;; Idle-timeout test (requires OPENAI_API_KEY)
  (def idle-run
    (run-idle-timeout-test! {:channel-idle-timeout-ms 1500 :buffer-size 1}))
  (:data (:event idle-run))

  ;; Overflow test with :block mode (no overflow queue)
  (def overflow-block-run
    (run-overflow-test! {:chunks 500 :overflow-mode :block :buffer-size 1 :consumer-delay-ms 10}))
  @(:consumed overflow-block-run))
