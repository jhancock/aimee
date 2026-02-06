(ns aimee.chat.executor
  (:require [aimee.chat.parser :as parser]
            [aimee.chat.sse :as chat-sse]
            [aimee.http :as http]
            [aimee.sse :as sse]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- build-body
  "Build the request body for chat completion."
  [{:keys [model messages stream? choices-n include-usage?]}]
  (json/generate-string
   (cond-> {:model model
            :messages messages
            :stream stream?
            :n choices-n}
     (and stream? include-usage?)
     (assoc :stream_options {:include_usage true}))))

(defn- build-request-opts
  "Build HTTP request options map."
  [{:keys [api-key headers body http-timeout-ms]}]
  {:headers (merge (cond-> {"content-type" "application/json"}
                     (non-blank-string? api-key)
                     (assoc "Authorization" (str "Bearer " api-key)))
                   headers)
   :body body
   :timeout http-timeout-ms})

(defn- ok-status? [status]
  (<= 200 status 299))

(defn- read-body-safe [stream]
  (try
    (slurp stream)
    (catch Exception _ "")))

(defn- handle-http-error!
  [channel-callbacks status body]
  ((:error! channel-callbacks)
   (ex-info "HTTP error" {:status status :body body})))

(defn streaming
  "Execute a streaming chat completion request.

  Passes through streaming behavior opts (:parse-chunks?, :accumulate?, :on-parse-error)
  to the SSE consumer.
  "
  [opts stop? stream-ref channel-callbacks]
  (let [{:keys [url api-key headers http-timeout-ms channel on-parse-error parse-chunks? accumulate?]} opts
        body (build-body opts)
        request-opts (build-request-opts
                      {:api-key api-key
                       :headers headers
                       :body body
                       :http-timeout-ms http-timeout-ms})]
    (if @stop?
      ((:complete! channel-callbacks) {:content "" :reason :stopped})
      (let [resp (http/post url (assoc request-opts :as :stream))
            status (:status resp)
            stream (:body resp)]
        (reset! stream-ref stream)
        (cond
          @stop?
          (do (try (.close stream) (catch Exception _))
              ((:complete! channel-callbacks) {:content "" :reason :stopped}))

          (not (ok-status? status))
          (let [body (read-body-safe stream)]
            (try (.close stream) (catch Exception _))
            (handle-http-error! channel-callbacks status body))

          :else
          (let [sse-callbacks (chat-sse/make-stream-handlers
                               {:emit! (:emit! channel-callbacks)
                                :complete! (:complete! channel-callbacks)
                                :error! (:error! channel-callbacks)
                                :stream stream
                                :on-parse-error on-parse-error
                                :parse-chunks? parse-chunks?})
                accumulator (cond
                              accumulate? parser/accumulate-content
                              parse-chunks? parser/accumulate-metadata
                              :else nil)]
            (sse/consume-sse!
             stream
             {:accumulator accumulator
              :initial-acc {:content ""}
              :stop? stop?
              :terminated? (:terminated? channel-callbacks)
              :on-event (:on-event sse-callbacks)
              :on-complete (:on-complete sse-callbacks)
              :on-error (:on-error sse-callbacks)})))))))

(defn non-streaming
  "Execute a non-streaming chat completion request."
  [opts channel-callbacks]
  (let [{:keys [url api-key headers http-timeout-ms]} opts
        body (build-body opts)
        request-opts (build-request-opts
                      {:api-key api-key
                       :headers headers
                       :body body
                       :http-timeout-ms http-timeout-ms})]
    (let [resp (http/post url (assoc request-opts :as :string))
          status (:status resp)]
      (if (ok-status? status)
        (let [{:keys [content finish-reason role tool-calls function-call usage refusal refusal?]}
              (parser/parse-final-response (:body resp))]
          ((:complete! channel-callbacks)
           (cond-> {:content content}
             finish-reason (assoc :finish-reason finish-reason)
             role (assoc :role role)
             tool-calls (assoc :tool-calls tool-calls)
             function-call (assoc :function-call function-call)
             usage (assoc :usage usage)
             refusal (assoc :refusal refusal)
             refusal? (assoc :refusal? true))))
        (handle-http-error! channel-callbacks status (:body resp))))))
