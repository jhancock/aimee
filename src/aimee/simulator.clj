(ns aimee.simulator
  (:require [aimee.chat.client :as chat]
            [aimee.util :as util]
            [aimee.chat.sse-helpers :as helpers]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defn start!
  "Dev helper: simulate the user request/response flow without Jetty.

  Opens an agent request/response stream to OpenAI. Set OPENAI_API_KEY in the
  environment.

  Required opts:
  - :model
  - :messages

  Optional opts:
  - :stream? (default false)

  Returns a map with :chan and :stop! function. Caller is responsible
  for consuming events from the channel.
  "
  [opts]
  (log/info "simulator starting request/response"
            {:thread (util/thread-info)})
  (let [ch (async/chan 64)
        result (chat/start-request! (assoc opts :channel ch))]
    (assoc result :chan ch)))

(defn consume-events!!
  "Dev helper: consume channel events with optional handlers (blocking).

  Options:
  - :chan (required)
  - :on-chunk (fn [event]) - receives {:event :chunk :data {...}}
  - :on-complete (fn [event]) - receives {:event :complete :data {...}}
  - :on-error (fn [event]) - receives {:event :error :data <exception>}

  Blocks the current thread while consuming events.
  "
  [{:keys [chan on-chunk on-complete on-error]}]
  (loop []
    (when-let [event (async/<!! chan)]
      (case (:event event)
        :chunk
        (when on-chunk
          (on-chunk event))

        :complete
        (do
          (log/info "stream complete"
                    {:thread (util/thread-info)})
          (when on-complete
            (on-complete event)))

        :error
        (do
          (log/error "stream error:"
                     {:error event
                      :thread (util/thread-info)})
          (when on-error
            (on-error event)))

        (log/warn "stream unknown event" event))
      (recur))))

(comment
  ;; Network helper for API-backed examples
  (def openai-api-url (or (System/getenv "OPENAI_API_URL")
                          (System/getenv "OPENAI_URL")
                          "https://api.openai.com/v1/chat/completions"))
  (def openai-api-key (System/getenv "OPENAI_API_KEY"))
  openai-api-key
  ;; Optional local override:
  ;; (require '[parker.config :as config])
  ;; (def openai-api-url (config/openai-url))
  ;; (def openai-api-key (config/openai-key))

  ;; Wait for the terminal event (:complete or :error) from a channel.
  (defn await-terminal-event!! [ch timeout-ms]
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

  ;; Local helpers for SSE-only tests (no network)
  (require '[aimee.sse :as sse]
           '[aimee.chat.sse :as chat-sse]
           '[aimee.chat.parser :as chat-parser]
           '[aimee.chat.options :as chat-options]
           '[aimee.chat.emitter :as chat-emitter]
           '[aimee.stress :as stress])

  ;; Example 0: Local channel smoke test (no network)
  (def local-ch (async/chan 3))
  (def local-chunks (atom []))
  (def local-complete (atom nil))
  (async/>!! local-ch {:event :chunk :data {:data "x"}})
  (async/>!! local-ch {:event :complete :data {:content "done"}})
  (async/close! local-ch)
  (consume-events!!
   {:chan local-ch
    :on-chunk #(swap! local-chunks conj %)
    :on-complete #(reset! local-complete %)})
  @local-chunks
  @local-complete

  ;; Example 0a: Auth resolution and validation (no network)
  ;; Priority: :api-key > :api-key-fn > :api-key-env; Authorization header also valid.
  (def auth-base
    {:channel (async/chan 1)
     :url openai-api-url
     :model "gpt-4o-mini"
     :messages [{:role "user" :content "auth check"}]})
  (chat-options/validate-opts! (assoc auth-base :api-key openai-api-key))
  (chat-options/validate-opts! (assoc auth-base :api-key-fn (fn [_opts] openai-api-key)))
  (chat-options/validate-opts! (assoc auth-base :api-key-env "OPENAI_API_KEY"))
  (chat-options/validate-opts!
   (assoc auth-base :api-key nil :headers {"Authorization" (str "Bearer " openai-api-key)}))
  ;; Runtime check using :api-key-fn
  (def ch-auth (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key-fn (fn [_opts] openai-api-key)
    :channel ch-auth
    :model "gpt-4o-mini"
    :stream? false
    :messages [{:role "user" :content "Say auth fn works."}]})
  (def event-auth (async/<!! ch-auth))
  event-auth

  ;; Example 1: Non-streaming call via chat client
  (def ch-1 (async/chan 1))
  (chat/start-request!
   {:url openai-api-url
    :api-key openai-api-key
    :channel ch-1
    :model "gpt-4o-mini"
    :stream? false
    :messages [{:role "user" :content "Count to 5."}]})
  (def event-1 (async/<!! ch-1))
  event-1

  ;; Example 2: Standard streaming with :parsed chunks and accumulation (default)
  (def stream-2
    (start! {:url openai-api-url
             :api-key openai-api-key
             :stream? true
             :model "gpt-4o-mini"
             :messages [{:role "user" :content "Say hello in two sentences."}]}))

  (loop []
    (when-let [event (async/<!! (:chan stream-2))]
      (case (:event event)
        :chunk
        (do
          (log/info "chunk event:" event)
          (log/info "chunk parsed:" (:parsed (:data event)))
          (log/info "simplified SSE:" (helpers/event->simplified-sse event))
          (recur))

        :complete
        (do
          (log/info "complete event:" event)
          (log/info "simplified SSE:" (helpers/event->simplified-sse event)))

        :error
        (do
          (log/error "error event:" event)
          (log/info "simplified SSE:" (helpers/event->simplified-sse event)))

        (do
          (log/warn "unknown event:" event)
          (recur)))))

  ;; Example 2b: Streaming with usage included (:include-usage? true)
  (def stream-2b
    (start! {:url openai-api-url
             :api-key openai-api-key
             :stream? true
             :include-usage? true
             :model "gpt-4o-mini"
             :messages [{:role "user" :content "Count to 3."}]}))

  (loop [event (async/<!! (:chan stream-2b))]
    (when event
      (if (= :complete (:event event))
        (do
          (log/info "complete usage:" (get-in event [:data :usage]))
          event)
        (recur (async/<!! (:chan stream-2b))))))

  ;; Example 3: Raw SSE proxy mode (no parsing, no accumulation)
  ;; Use this when forwarding chunks directly to a browser
  (def stream-3
    (start! {:url openai-api-url
             :api-key openai-api-key
             :stream? true
             :parse-chunks? false
             :accumulate? false
             :model "gpt-4o-mini"
             :messages [{:role "user" :content "Quick greeting."}]}))

  (consume-events!!
   {:chan (:chan stream-3)
    :on-chunk (fn [event]
                ;; Raw chunk has :id, :type, :data (JSON string) but no :parsed
                (log/info "Raw chunk:" (:data (:data event))))
    :on-complete (fn [_event]
                   (log/info "Stream complete"))})

  ;; Example 4: Parsed chunks without accumulation
  ;; Get deltas via :parsed but don't build content
  (def stream-4
    (start! {:url openai-api-url
             :api-key openai-api-key
             :stream? true
             :accumulate? false
             :model "gpt-4o-mini"
             :messages [{:role "user" :content "Count from 1 to 3."}]}))

  (consume-events!!
   {:chan (:chan stream-4)
    :on-chunk (fn [event]
                (when-let [parsed (:parsed (:data event))]
                  (log/info "Delta:" (:content parsed))))
    :on-complete (fn [event]
                   ;; content is empty since accumulation is off
                   (log/info "Complete - content empty:" (:content (:data event))))})

  ;; Example 5: Local SSE stream with a non-chunk payload (should be skipped)
  (def sse-data
    (str
     "data: {\"object\":\"other.event\",\"data\":{}}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-5 (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8")))
  (def seen-events-5 (atom []))
  (def final-info-5 (atom nil))
  (def handlers-5 (chat-sse/make-stream-handlers
                   {:emit! (fn [event _] (swap! seen-events-5 conj event))
                    :complete! (fn [info] (reset! final-info-5 info))
                    :error! (fn [ex] (swap! seen-events-5 conj {:event :error :data ex}))
                    :stream nil
                    :parse-chunks? true
                    :on-parse-error :stop}))
  (sse/consume-sse!
   input-5
   {:accumulator chat-parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-5)
    :on-complete (:on-complete handlers-5)
    :on-error (:on-error handlers-5)})
  @seen-events-5
  @final-info-5

  ;; Example 5b: Local SSE stream with usage-only final chunk
  (def sse-usage
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}\n\n"
     "data: [DONE]\n\n"))
  (def input-usage (java.io.ByteArrayInputStream. (.getBytes sse-usage "UTF-8")))
  (def usage-final (atom nil))
  (def usage-handlers (chat-sse/make-stream-handlers
                       {:emit! (fn [_event _] nil)
                        :complete! (fn [info] (reset! usage-final info))
                        :error! (fn [ex] (reset! usage-final {:error ex}))
                        :stream nil
                        :parse-chunks? true
                        :on-parse-error :stop}))
  (sse/consume-sse!
   input-usage
   {:accumulator chat-parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event usage-handlers)
    :on-complete (:on-complete usage-handlers)
    :on-error (:on-error usage-handlers)})
  @usage-final

  ;; Example 5c: Local SSE stream with refusal delta
  (def sse-refusal
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"refusal\":\"Sorry, I can't help with that.\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-refusal (java.io.ByteArrayInputStream. (.getBytes sse-refusal "UTF-8")))
  (def refusal-final (atom nil))
  (def refusal-handlers (chat-sse/make-stream-handlers
                         {:emit! (fn [_event _] nil)
                          :complete! (fn [info] (reset! refusal-final info))
                          :error! (fn [ex] (reset! refusal-final {:error ex}))
                          :stream nil
                          :parse-chunks? true
                          :on-parse-error :stop}))
  (sse/consume-sse!
   input-refusal
   {:accumulator chat-parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event refusal-handlers)
    :on-complete (:on-complete refusal-handlers)
    :on-error (:on-error refusal-handlers)})
  @refusal-final

  ;; Example 5d: Parse error policy (:stop) with malformed JSON (local SSE)
  ;; In this local fixture, :stop emits :error; [DONE] still ends the stream.
  (def sse-bad-stop
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"BROKEN\"}}\n\n"
     "data: [DONE]\n\n"))
  (def input-bad-stop (java.io.ByteArrayInputStream. (.getBytes sse-bad-stop "UTF-8")))
  (def bad-stop-events (atom []))
  (def bad-stop-complete (atom nil))
  (def handlers-bad-stop
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! bad-stop-events conj event))
      :complete! (fn [info] (reset! bad-stop-complete info))
      :error! (fn [ex] (swap! bad-stop-events conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-bad-stop
   {:accumulator chat-parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-bad-stop)
    :on-complete (:on-complete handlers-bad-stop)
    :on-error (:on-error handlers-bad-stop)})
  @bad-stop-events
  @bad-stop-complete

  ;; Example 5e: Parse error policy (:continue) with malformed JSON (local SSE)
  ;; The malformed chunk should be skipped and stream should still complete.
  (def sse-bad-continue
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"BROKEN\"}}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-bad-continue (java.io.ByteArrayInputStream. (.getBytes sse-bad-continue "UTF-8")))
  (def bad-continue-events (atom []))
  (def bad-continue-complete (atom nil))
  (def handlers-bad-continue
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! bad-continue-events conj event))
      :complete! (fn [info] (reset! bad-continue-complete info))
      :error! (fn [ex] (swap! bad-continue-events conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :continue}))
  (sse/consume-sse!
   input-bad-continue
   {:accumulator chat-parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-bad-continue)
    :on-complete (:on-complete handlers-bad-continue)
    :on-error (:on-error handlers-bad-continue)})
  @bad-continue-events
  @bad-continue-complete

  ;; Example 5f: Starter backpressure demo (local SSE)
  ;; :queue mode should keep stream alive while consumer lags.
  (def overflow-starter
    (stress/run-overflow-test! {:chunks 300
                                :overflow-max 50
                                :overflow-mode :queue
                                :buffer-size 1
                                :consumer-delay-ms 5}))
  (Thread/sleep 1000)
  @(:consumed overflow-starter)
  @(:terminated overflow-starter)
  ;; :block mode applies immediate backpressure and should terminate cleanly.
  (def overflow-block-starter
    (stress/run-overflow-test! {:chunks 150
                                :overflow-mode :block
                                :buffer-size 1
                                :consumer-delay-ms 5}))
  (Thread/sleep 1000)
  @(:consumed overflow-block-starter)
  @(:terminated overflow-block-starter)

  ;; Example 5g: Channel lifecycle guarantee on :complete (local, no network)
  ;; Terminal event is delivered, then channel closes.
  (def lifecycle-ch-complete (async/chan 1))
  (def lifecycle-cb-complete
    (chat-emitter/make-channel-callbacks
     lifecycle-ch-complete
     {:overflow-max 10 :overflow-mode :queue}))
  ((:complete! lifecycle-cb-complete) {:content "done"})
  (def lifecycle-complete-event (async/<!! lifecycle-ch-complete))
  lifecycle-complete-event
  (async/<!! lifecycle-ch-complete)

  ;; Example 5h: Channel lifecycle guarantee on :error (local, no network)
  ;; Terminal error is delivered, then channel closes.
  (def lifecycle-ch-error (async/chan 1))
  (def lifecycle-cb-error
    (chat-emitter/make-channel-callbacks
     lifecycle-ch-error
     {:overflow-max 10 :overflow-mode :queue}))
  ((:error! lifecycle-cb-error) (ex-info "simulated failure" {:type :simulated}))
  (def lifecycle-error-event (async/<!! lifecycle-ch-error))
  lifecycle-error-event
  (async/<!! lifecycle-ch-error)

  ;; Example 6: HTTP error flow (non-2xx response)
  (def ch-6 (async/chan 1))
  (chat/start-request!
   {:url "https://httpbin.org/status/401"
    :api-key "invalid-key"
    :channel ch-6
    :model "gpt-4o-mini"
    :stream? false
    :messages [{:role "user" :content "This will not succeed."}]})
  (def event-6 (async/<!! ch-6))
  event-6
  (:data event-6)

  ;; Example 7: Idle-timeout after initial progress (deterministic timeout)
  ;; Read one early chunk, then stop consuming to force channel stall and timeout.
  (def ch-7 (async/chan 1))
  (def result-7 (chat/start-request!
                 {:url openai-api-url
                  :api-key openai-api-key
                  :channel ch-7
                  :model "gpt-4o-mini"
                  :stream? true
                  :channel-idle-timeout-ms 1500
                  :messages [{:role "user"
                              :content "Write 1200 words about a library cat."}]}))
  (def first-7 (async/<!! ch-7))
  (:event first-7)
  (def event-7
    (if (#{:complete :error} (:event first-7))
      first-7
      (do
        (Thread/sleep 2500)
        (await-terminal-event!! ch-7 10000))))
  event-7
  (:data event-7)

  ;; Example 7b: Idle-timeout without consuming (confirm terminal :timeout)
  ;; Use an unbuffered channel so no consumer means zero progress from the start.
  ;; After timeout elapses, first terminal read should be {:reason :timeout}.
  (def ch-7b (async/chan))
  (def result-7b (chat/start-request!
                  {:url openai-api-url
                   :api-key openai-api-key
                   :channel ch-7b
                   :model "gpt-4o-mini"
                   :stream? true
                   :channel-idle-timeout-ms 1500
                   :messages [{:role "user"
                               :content "Write 1200 words about a library cat."}]}))
  (Thread/sleep 2500)
  (def event-7b (await-terminal-event!! ch-7b 10000))
  event-7b
  (:data event-7b)

  ;; Example 8: Stop a streaming request and confirm :stopped (deterministic)
  ;; Use a long prompt and wait for the terminal event after stop.
  (def ch-8 (async/chan 1))
  (def result-8 (chat/start-request!
                 {:url openai-api-url
                  :api-key openai-api-key
                  :channel ch-8
                  :model "gpt-4o-mini"
                  :stream? true
                  :messages [{:role "user"
                              :content "Write 1200 words about a library cat."}]}))
  (Thread/sleep 100)
  ((:stop! result-8))
  (def event-8 (await-terminal-event!! ch-8 10000))
  event-8
  (:data event-8)

  ;; Example 9: Force idle-timeout by blocking the consumer
  ;; The channel fills immediately, so no progress is recorded and timeout fires.
  (def ch-9 (async/chan 1))
  (def result-9 (chat/start-request!
                 {:url openai-api-url
                  :api-key openai-api-key
                  :channel ch-9
                  :model "gpt-4o-mini"
                  :stream? true
                  :channel-idle-timeout-ms 200
                  :messages [{:role "user"
                              :content "Write 1000 words about a library cat."}]}))
  (Thread/sleep 1000)
  (def event-9 (async/<!! ch-9))
  event-9
  (:data event-9)

  ;; Example 9b: No-timeout contrast (:channel-idle-timeout-ms nil)
  ;; Even with delayed consumption, terminal event should be normal completion, not :timeout.
  (def ch-9b (async/chan 1))
  (def result-9b (chat/start-request!
                  {:url openai-api-url
                   :api-key openai-api-key
                   :channel ch-9b
                   :model "gpt-4o-mini"
                   :stream? true
                   :channel-idle-timeout-ms nil
                   :messages [{:role "user"
                               :content "Write 600 words about a library cat."}]}))
  (Thread/sleep 2000)
  (def event-9b (await-terminal-event!! ch-9b 30000))
  event-9b
  (:data event-9b)

  ;; For more stress scenarios, see aimee.stress
  )
