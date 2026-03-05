(ns aimee.chat.ring
  "Ring adapter for SSE streaming.
   
   Provides a Ring StreamableResponseBody implementation that consumes
   Aimee event channels and streams them as SSE.
   
   Note: Requires ring/ring-core as a dependency (included in aimee's deps).
  "
  (:require [aimee.chat.sse-helpers :as sse-helpers]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [ring.core.protocols :as protocols]))

(defn consume-channel!
  "Consumes events from channel, calls handlers, writes SSE to output-stream.
   
   Blocking - runs on caller's thread.
   
   Handlers (all optional):
   - :on-chunk (fn [event]) → return event/nil/:stop
   - :on-complete (fn [event]) → return event/nil/:stop
   - :on-error (fn [event]) → return event/nil/:stop
   
   Handler return values:
   - event map → emit this event (can be modified)
   - nil → skip this event (only meaningful for :chunk)
   - :stop → terminate stream immediately
   
   SSE [DONE] emission:
   - Primary: :chunk with :done? true in :parsed
   - Fallback: :complete event (if [DONE] not yet sent)
   - Error: :error event (if [DONE] not yet sent)
   
   Example:
     (consume-channel! ch output-stream
       {:on-complete (fn [e] (async/go (db/save! e)) e)
        :on-error (fn [e] (log/error e) :stop)})"
  [channel output-stream {:keys [on-chunk on-complete on-error]}]
  (with-open [writer (io/writer output-stream)]
    (loop [done-sent? false]
      (when-let [event (async/<!! channel)]
        (case (:event event)
          :chunk
          (let [is-terminal? (get-in event [:data :parsed :done?])
                result (if on-chunk (on-chunk event) event)]
            (cond
              (= :stop result)
              (do
                (when-not done-sent?
                  (.write writer (sse-helpers/format-sse-done))
                  (.flush writer))
                nil)
              
              (nil? result)
              (recur done-sent?)
              
              is-terminal?
              (do
                (when-not done-sent?
                  (.write writer (sse-helpers/format-sse-done))
                  (.flush writer))
                (recur true))
              
              :else
              (do
                (when-let [frame (sse-helpers/event->simplified-sse result)]
                  (.write writer frame)
                  (.flush writer))
                (recur done-sent?))))
          
          :complete
          (let [result (if on-complete (on-complete event) event)]
            (when (and (not= :stop result) result)
              (when-not done-sent?
                (.write writer (sse-helpers/format-sse-done))
                (.flush writer)))
            nil)
          
          :error
          (let [result (if on-error (on-error event) event)]
            (when-not done-sent?
              (.write writer (sse-helpers/format-sse-done))
              (.flush writer))
            nil))))))

(defn ->ring-stream
  "Creates a Ring StreamableResponseBody for SSE streaming.
   
   Returns an object that implements StreamableResponseBody protocol.
   No global protocol extension needed.
   
   Example:
     {:status 200
      :headers {\"content-type\" \"text/event-stream\"}
      :body (ring/->ring-stream event-channel
               {:on-complete (fn [e] (db/save! e) e)
                :on-error (fn [e] (log/error e) :stop)})}
   
   Note: Handlers run on the HTTP request thread. For async operations,
   spawn your own thread/go-block inside the handler."
  [channel handlers]
  (reify protocols/StreamableResponseBody
    (write-body-to-stream [this _response output-stream]
      (consume-channel! channel output-stream handlers))))
