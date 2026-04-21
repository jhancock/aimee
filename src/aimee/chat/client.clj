(ns aimee.chat.client
  (:require [aimee.chat.emitter :as emitter]
            [aimee.chat.executor :as executor]
            [aimee.chat.options :as options]
            [aimee.chat.timeout :as timeout]
            [aimee.util :as util]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defn- make-stop-fn
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
  - API credentials via one of:
    - :api-key
    - :api-key-fn (called with opts map when arity supports it, otherwise 0-arity)
    - :headers containing Authorization

   Streaming behavior opts (when :stream? true):
   - :accumulate? (default true) - When true, accumulates content to build :content
                    in :complete event. When false, skips content accumulation and :content is empty
                    (metadata like :api-finish-reason is still captured).
   - :on-parse-error (default :stop) - When :stop, emit :error event and close stream on
                        parse failure. When :continue, log warning and skip bad chunk.

   Backpressure opts:
   - :queue-capacity (default 1000) - Capacity of overflow queue when :backpressure is :queue
   - :backpressure (default :queue) - :queue creates bounded overflow queue, :block blocks immediately

  Timeout opts:
  - :channel-idle-timeout-ms - Abort if no progress events emitted for this duration
  - :http-timeout-ms - HTTP request timeout in milliseconds

   Other opts:
   - :stream? (default false) - Enable streaming response
   - :headers - Additional HTTP headers
   - :api-params (default nil) - Map merged directly into the API request body
     (wire-format keys). E.g. {:reasoning {:effort "high"} :temperature 0.7}

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
                             (merge (select-keys opts [:queue-capacity :backpressure])
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
