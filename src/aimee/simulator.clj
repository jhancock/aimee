(ns aimee.simulator
  (:require [aimee.chat.client :as chat]
            [aimee.util :as util]
            [aimee.sse-helpers :as helpers]
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
  ;; REPL helper (uses parker.config for convenience)
  (require '[parker.config :as config])
  (config/openai-url)

  ;; Local helpers for SSE-only tests (no network)
  (require '[aimee.sse :as sse]
           '[aimee.chat.sse :as chat-sse]
           '[aimee.chat.parser :as chat-parser])

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

  ;; Example 1: Non-streaming call via chat client
  (def ch-1 (async/chan 1))
  (chat/start-request!
   {:url (config/openai-url)
    :api-key (config/openai-key)
    :channel ch-1
    :model "gpt-4o-mini"
    :stream? false
    :messages [{:role "user" :content "Count to 5."}]})
  (def event-1 (async/<!! ch-1))
  event-1
  (:data event-1)

  ;; Example 2: Standard streaming with :parsed chunks and accumulation (default)
  (def stream-2
    (start! {:url (config/openai-url)
             :api-key (config/openai-key)
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
          (log/info "simplified:" (helpers/event->simplified-sse event))
          (recur))

        :complete
        (do
          (log/info "complete:" event)
          (log/info "simplified:" (helpers/event->simplified-sse event)))

        :error
        (do
          (log/error "error event:" event)
          (log/info "simplified:" (helpers/event->simplified-sse event)))

        (do
          (log/warn "unknown event:" event)
          (recur)))))

  ;; Example 2b: Streaming with usage included (:include-usage? true)
  (def stream-2b
    (start! {:url (config/openai-url)
             :api-key (config/openai-key)
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
    (start! {:url (config/openai-url)
             :api-key (config/openai-key)
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
    (start! {:url (config/openai-url)
             :api-key (config/openai-key)
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

  ;; Example 7: Idle-timeout with delayed consumer
  (def ch-7 (async/chan 1))
  (def result-7 (chat/start-request!
                 {:url (config/openai-url)
                  :api-key (config/openai-key)
                  :channel ch-7
                  :model "gpt-4o-mini"
                  :stream? true
                  :channel-idle-timeout-ms 1500
                  :messages [{:role "user" :content "Stream for a while."}]}))
  (Thread/sleep 3000)
  (def event-7 (async/<!! ch-7))
  event-7
  (:data event-7)

  ;; Example 7b: Idle-timeout without consuming (confirm timeout event)
  (def ch-7b (async/chan 1))
  (def result-7b (chat/start-request!
                  {:url (config/openai-url)
                   :api-key (config/openai-key)
                   :channel ch-7b
                   :model "gpt-4o-mini"
                   :stream? true
                   :channel-idle-timeout-ms 1500
                   :messages [{:role "user" :content "Keep streaming until timeout."}]}))
  (Thread/sleep 3000)
  (def event-7b (async/<!! ch-7b))
  event-7b
  (:data event-7b)

  ;; Example 8: Stop a streaming request and confirm :stopped
  ;; This should emit a :complete event with {:reason :stopped}.
  (def ch-8 (async/chan 1))
  (def result-8 (chat/start-request!
                 {:url (config/openai-url)
                  :api-key (config/openai-key)
                  :channel ch-8
                  :model "gpt-4o-mini"
                  :stream? true
                  :messages [{:role "user" :content "Stream slowly and keep going."}]}))
  (Thread/sleep 200)
  ((:stop! result-8))
  (def event-8 (async/<!! ch-8))
  (:data event-8)

  ;; Example 9: Force idle-timeout by blocking the consumer
  ;; The channel fills immediately, so no progress is recorded and timeout fires.
  (def ch-9 (async/chan 1))
  (def result-9 (chat/start-request!
                 {:url (config/openai-url)
                  :api-key (config/openai-key)
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

  ;; For more stress scenarios, see aimee.stress
  )
