(ns aimee.chat.sse
  (:require [aimee.chat.events :as events]
            [aimee.chat.parser :as parser]
            [clojure.tools.logging :as log]))

(defn make-stream-handlers
  "Create handlers for SSE consumption.

  Returns map with:
  - :on-event - wraps events as stream chunks
  - :on-complete - calls complete! and closes stream
  - :on-error - calls error! and closes stream

  Options:
  - :emit! - channel emitter function
  - :complete! - completion callback
  - :error! - error callback
  - :stream - InputStream to close on completion/error
  - :parse-chunks? - true or false. When true, parse each chunk and include :parsed key
                     with {:content, :finish-reason, :role, :tool-calls, :function-call, :done?}.
                     When false, chunks contain
                     only raw SSE data. (Normalized by validate-opts!)
  - :on-parse-error - :stop or :continue. When :stop, emit :error event and close stream on
                     parse failure. When :continue, log warning and skip bad chunk.
                     (Normalized by validate-opts!)
  "
  [{:keys [emit! complete! error! stream parse-chunks? on-parse-error]}]
  {:on-event (fn [raw-event]
               (try
                 ;; Parse to validate JSON format (throws on error)
                 (let [parsed (when (and parse-chunks? (:data raw-event))
                                (parser/parse-sse-event! (:data raw-event)))
                       ;; If we get here, parsing succeeded - emit the chunk with parsed data
                       chunk (cond-> raw-event
                               parse-chunks? (assoc :parsed parsed))]
                   (when-not (or (and parsed (:skip? parsed))
                                 (and parsed (:done? parsed) (empty? (:content parsed))))
                     (emit! (events/make-event :chunk chunk) false)))
                 (catch Exception ex
                   (if (= on-parse-error :stop)
                     (do
                       (error! (ex-info "Failed to parse SSE chunk"
                                        {:raw-event raw-event}
                                        ex))
                       (when stream
                         (try (.close stream)
                              (catch Exception _))))
                     (log/warn "parse error; skipping chunk"
                               {:raw-event raw-event
                                :error (.getMessage ex)})))))
   :on-complete (fn [info]
                  (when complete!
                    (complete! info))
                  (when stream
                    (try (.close stream)
                         (catch Exception _))))
   :on-error (fn [ex]
               (when error!
                 (error! ex))
               (when stream
                 (try (.close stream)
                      (catch Exception _))))})
