(ns aimee.chat.client
  (:require [aimee.chat.emitter :as emitter]
            [aimee.chat.executor :as executor]
            [aimee.chat.options :as options]
            [aimee.chat.timeout :as timeout]
            [aimee.util :as util]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defn make-stop-fn
  "Create a stop function that cancels the request."
  [stop? stream-ref]
  (fn []
    (reset! stop? true)
    (when-let [stream @stream-ref]
      (try (.close stream) (catch Exception _)))))

(defn start-request!
  "Start a chat completion request.

  Required opts:
  - :channel - core.async channel (caller-created, caller-owned)
  - :model
  - :messages
  - :url
  - :api-key

  Streaming behavior opts (when :stream? true):
  - :parse-chunks? (default true) - When true, each :chunk event includes :parsed key
                    with {:content, :finish-reason, :role, :tool-calls, :function-call, :done?}.
                    When false, chunks contain
                    only raw SSE data without :parsed.
  - :accumulate? (default true) - When true, accumulates content to build :content
                   in :complete event. Uses :parsed when available, otherwise parses
                   from raw :data. When false, skips content accumulation and :content is empty
                   (metadata like :finish-reason is still captured when :parse-chunks? is true).
  - :on-parse-error (default :stop) - When :stop, emit :error event and close stream on
                        parse failure. When :continue, log warning and skip bad chunk.

  Backpressure opts:
  - :overflow-max (default 10000) - Max queued events before applying backpressure
  - :overflow-mode (default :queue) - :queue creates lazy overflow queue, :block blocks immediately

  Timeout opts:
  - :channel-idle-timeout-ms - Abort if no progress events emitted for this duration
  - :http-timeout-ms - HTTP request timeout in milliseconds

  Other opts:
  - :stream? (default false) - Enable streaming response
  - :headers - Additional HTTP headers

  Returns map with:
  - :stop! - function to cancel the request

  Channel ownership: caller creates and owns the channel; this library only
  writes events into it and never allocates channels internally.
  "
  [{:keys [channel] :as opts}]
  (let [opts (options/validate-opts! opts)]

    (let [stop? (atom false)
          stream-ref (atom nil)
          terminated? (atom false)
          channel-callbacks (emitter/make-channel-callbacks
                             channel
                             (merge (select-keys opts [:overflow-max :overflow-mode])
                                    {:terminated? terminated?}))
          stop-fn (make-stop-fn stop? stream-ref)
          {:keys [last-progress emit!]} channel-callbacks]
      (timeout/start-idle-timeout!
       {:channel-idle-timeout-ms (:channel-idle-timeout-ms opts)
        :last-progress last-progress
        :terminated? terminated?
        :emit! emit!
        :stop-fn stop-fn})
      ;; JDK 21+ only: core.async io-thread uses the :io executor, which can use
      ;; virtual threads depending on core.async vthread configuration.
      (async/io-thread
       (try
         (log/info "chat request worker started"
                   {:thread (util/thread-info)})
         (if @stop?
           ((:complete! channel-callbacks) {:content "" :reason :stopped})
           (if (:stream? opts)
             (executor/streaming opts stop? stream-ref channel-callbacks)
             (executor/non-streaming opts channel-callbacks)))
         (catch Exception ex
           ((:error! channel-callbacks) ex))))
      {:stop! stop-fn})))
