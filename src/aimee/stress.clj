(ns aimee.stress
  (:require [aimee.chat.client :as chat]
            [aimee.chat.emitter :as emitter]
            [aimee.chat.parser :as parser]
            [aimee.chat.sse :as chat-sse]
            [aimee.sse :as sse]
            [clojure.core.async :as async]))

(defn make-sse-sample
  "Create an SSE sample with n chunk events and a [DONE] sentinel."
  [n]
  (str (apply str
              (repeat n "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"))
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

(defn wait-until-terminated!!
  "Wait for a run map from run-overflow-test! to report termination.

  Returns {:terminated? <bool> :consumed <long> :consumer-finished? <bool> :timeout? <bool>}."
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
  - :openai-api-url (default OPENAI_API_URL, falls back to OPENAI_URL)
  - :openai-api-key (default OPENAI_API_KEY)
  - :channel-idle-timeout-ms (default 1500)
  - :buffer-size (default 0)
  - :max-wait-ms (default 5000)
  - :message (default long prompt to force multi-chunk streaming)
  "
  [{:keys [openai-api-url openai-api-key openai-url openai-key
           channel-idle-timeout-ms buffer-size max-wait-ms message]
    :or {openai-api-url (or (System/getenv "OPENAI_API_URL")
                            (System/getenv "OPENAI_URL")
                            "https://api.openai.com/v1/chat/completions")
         openai-api-key (System/getenv "OPENAI_API_KEY")
         channel-idle-timeout-ms 1500
         buffer-size 0
         max-wait-ms 5000
         message "Write 1200 words about a small cat exploring a library."}}]
  (let [ch (if (pos? buffer-size)
             (async/chan buffer-size)
             (async/chan))
        result (chat/start-request!
                {:url (or openai-api-url openai-url)
                 :api-key (or openai-api-key openai-key)
                 :channel ch
                 :model "gpt-4o-mini"
                 :stream? true
                 :channel-idle-timeout-ms channel-idle-timeout-ms
                 :messages [{:role "user" :content message}]})]
    (Thread/sleep (* 2 channel-idle-timeout-ms))
    (let [{:keys [events terminal timeout? closed?]} (collect-until-terminal!! ch max-wait-ms)]
      {:result result
       :event (or terminal (last events))
       :event-count (count events)
       :timeout? timeout?
       :closed? closed?})))

(defn run-parse-chunks-test!
  "Test :parse-chunks? option with local SSE stream.

  Options:
  - :parse-chunks? (default true) - When true, chunks include :parsed key
  - :chunks (default 10)
  - :max-wait-ms (default 5000)
  "
  [{:keys [parse-chunks? chunks overflow-max overflow-mode max-wait-ms]
    :or {parse-chunks? true chunks 10 overflow-max 100 overflow-mode :queue max-wait-ms 5000}}]
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
    (let [{:keys [events timeout? closed?]} (collect-until-terminal!! ch max-wait-ms)
          chunk (first (filter #(= :chunk (:event %)) events))]
      {:parse-chunks? parse-chunks?
       :has-parsed? (contains? (:data chunk) :parsed)
       :parsed (:parsed (:data chunk))
       :event-count (count events)
       :timeout? timeout?
       :closed? closed?})))

(defn run-accumulate-test!
  "Test :accumulate? option with local SSE stream.

  Options:
  - :accumulate? (default true) - When true, builds content
  - :chunks (default 10)
  - :max-wait-ms (default 5000)
  "
  [{:keys [accumulate? chunks overflow-max overflow-mode max-wait-ms]
    :or {accumulate? true chunks 10 overflow-max 100 overflow-mode :queue max-wait-ms 5000}}]
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
    (let [{:keys [events timeout? closed?]} (collect-until-terminal!! ch max-wait-ms)]
      {:accumulate? accumulate?
       :event-count (count events)
       :content (when-let [complete (first (filter #(= :complete (:event %)) events))]
                  (:content (:data complete)))
       :timeout? timeout?
       :closed? closed?})))

(comment
  ;; Network helper for API-backed examples
  (def openai-api-url (or (System/getenv "OPENAI_API_URL")
                          (System/getenv "OPENAI_URL")
                          "https://api.openai.com/v1/chat/completions"))
  (def openai-api-key (System/getenv "OPENAI_API_KEY"))
  ;; Optional local override:
  ;; (require '[parker.config :as config])
  ;; (def openai-api-url (config/openai-url))
  ;; (def openai-api-key (config/openai-key))

  ;; Overflow stress test (local SSE stream, no network)
  (def overflow-run
    (run-overflow-test! {:chunks 2000 :overflow-max 100 :buffer-size 1 :consumer-delay-ms 10}))
  (wait-until-terminated!! overflow-run 30000)

  ;; Parse-chunks test (local SSE stream, no network)
  (run-parse-chunks-test! {:parse-chunks? true :chunks 5})
  (run-parse-chunks-test! {:parse-chunks? false :chunks 5})

  ;; Accumulate test (local SSE stream, no network)
  (run-accumulate-test! {:accumulate? true :chunks 5})
  (run-accumulate-test! {:accumulate? false :chunks 5})

  ;; Idle-timeout test (requires OPENAI_API_KEY)
  (def idle-run
    (run-idle-timeout-test! {:openai-api-url openai-api-url
                             :openai-api-key openai-api-key
                             :channel-idle-timeout-ms 1500
                             :buffer-size 0}))
  (:data (:event idle-run))

  ;; Overflow test with :block mode (no overflow queue)
  (def overflow-block-run
    (run-overflow-test! {:chunks 500 :overflow-mode :block :buffer-size 1 :consumer-delay-ms 10}))
  (wait-until-terminated!! overflow-block-run 30000))
