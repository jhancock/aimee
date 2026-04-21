(ns aimee.chat.options
  (:require [clojure.core.async.impl.protocols :as async-proto]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn defaults
  "Return the default options for chat completions.

  Intended for REPL discovery; required opts are omitted.

  Note: refusal responses are normalized into :content (with :refusal? true)
  so a basic consumer can display a single text field.
  "
  []
  {;; When true, the response is streamed via SSE. Each :chunk event contains a
   ;; delta of the response as it arrives. When false, the full response is
   ;; returned in a single :complete event.
   :stream? false

    ;; When true, text from all chunks is concatenated and included in the
   ;; :complete event's :content field. Useful when you want the full response
   ;; text without manually accumulating chunks.
   :accumulate? true

   ;; How to handle JSON parse failures during streaming:
   ;;   :stop     — emit an :error event and close the stream
   ;;   :continue — log a warning and skip the malformed chunk
   :on-parse-error :stop

    ;; Backpressure strategy when the channel is full:
    ;;   :queue — create a bounded queue (capacity set by :queue-capacity) and drain
    ;;            in a background thread; preserves events until consumer catches up.
    ;;   :block — block the producer thread until the channel has capacity;
    ;;            simpler but may stall the SSE stream since it's connected to the HTTP request thread
    :backpressure :queue

    ;; Maximum capacity of the overflow queue (used only in :queue mode).
    ;; Events first attempt direct write to the channel. When the channel is full,
    ;; an overflow queue is created with this capacity. Once the queue reaches
    ;; this size, the producer thread blocks until space is available.
    :queue-capacity 1000

   ;; Milliseconds to wait for the consumer to accept an event before aborting.
   ;; This protects against slow or blocked consumers: if no event is
   ;; successfully written to the channel for this duration, emits :complete
   ;; with :reason :timeout and closes the stream.
   ;;
   ;; Progress is recorded only when the consumer takes an event...adding to
   ;; the overflow queue does not count. The check runs via aimee.scheduler
   ;; (a shared daemon thread) which auto-shuts down after 60 seconds of
   ;; inactivity. When nil, no idle timeout is applied.
   :channel-idle-timeout-ms nil

   ;; Milliseconds to wait for the HTTP response before aborting. This is the
   ;; standard HTTP client timeout for the initial connection and response,
   ;; distinct from the streaming duration. When nil, the HTTP client uses
   ;; its default behavior.
   :http-timeout-ms nil

   ;; Additional HTTP headers to include in the request. Merged with the
   ;; default Content-Type and Authorization headers.
   :headers nil

   ;; When true (and :stream? is true), requests that the API include token
   ;; usage statistics in the final streaming chunk. Usage is automatically
   ;; included when :stream? is false.
   :include-usage? false

   ;; Number of completion choices to request. 
   ;; The chat completions protocol allows more than 1 choice. Currently fixed at 1. 
   :choices-n 1

   ;; Maximum data lines to accept in a single SSE event before forcing
   ;; a flush. Protects against unbounded memory growth from malformed
   ;; or malicious input.
   :max-data-lines 1000

   ;; Map of additional parameters merged directly into the API request body.
   ;; Use wire-format key names (snake_case). Merged after all library-managed
   ;; fields, so API params can override defaults. Nil by default.
   ;;
   ;; Examples:
   ;;   {:reasoning {:effort "high"}}
   ;;   {:temperature 0.7 :max_tokens 4096}
   ;;   {:response_format {:type "json_object"} :seed 42}
   :api-params nil})

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- auth-header-value
  [headers]
  (some #(get headers %)
        ["Authorization" "authorization" :Authorization :authorization]))

(defn- has-auth-header?
  [headers]
  (non-blank-string? (auth-header-value headers)))

(defn- call-api-key-fn
  [api-key-fn opts]
  (try
    (api-key-fn opts)
    (catch clojure.lang.ArityException _
      (api-key-fn))))

(defn- resolve-api-key
  [{:keys [api-key api-key-fn] :as opts}]
  (let [opts (if (non-blank-string? api-key)
               opts
               (dissoc opts :api-key))
        resolved (or (when (non-blank-string? api-key)
                       api-key)
                     (when (ifn? api-key-fn)
                       (let [value (call-api-key-fn api-key-fn opts)]
                         (when (non-blank-string? value)
                           value))))]
    (cond-> opts
      resolved (assoc :api-key resolved))))

(defn- validate-auth!
  [{:keys [api-key headers] :as opts}]
  (when-not (or (non-blank-string? api-key)
                (has-auth-header? headers))
    (throw (ex-info "Missing API credentials"
                    {:type :missing-api-credentials
                     :hint "Provide :api-key, :api-key-fn, or Authorization header"})))
  opts)

(s/def ::channel (s/and some? #(satisfies? async-proto/Channel %)))
(s/def ::url non-blank-string?)
(s/def ::api-key non-blank-string?)
(s/def ::api-key-fn ifn?)
(s/def ::model non-blank-string?)
(s/def ::messages (s/and sequential? seq))
(s/def ::stream? boolean?)
(s/def ::accumulate? boolean?)
(s/def ::on-parse-error #{:stop :continue})
(s/def ::queue-capacity pos-int?)
(s/def ::backpressure #{:queue :block})
(s/def ::channel-idle-timeout-ms (s/nilable nat-int?))
(s/def ::http-timeout-ms (s/nilable nat-int?))
(s/def ::headers (s/nilable map?))

(s/def ::include-usage? boolean?)

(s/def ::choices-n #{1})
(s/def ::max-data-lines (s/nilable pos-int?))
(s/def ::api-params (s/nilable map?))

(s/def ::opts
  (s/keys :req-un [::channel ::url ::model ::messages]
           :opt-un [::stream?
                    ::api-key
                    ::api-key-fn
                    ::accumulate?
                   ::on-parse-error
                   ::queue-capacity
                   ::backpressure
                   ::channel-idle-timeout-ms
                   ::http-timeout-ms
                   ::headers
                    ::include-usage?
                   ::choices-n
                   ::max-data-lines
                   ::api-params]))

(defn validate-opts!
  "Validate and normalize required options. Throws ex-info if invalid.

  Returns normalized options map with all defaults applied.
  "
  [opts]
  (let [opts (-> (merge (defaults) opts)
                 (resolve-api-key))]
    (when-not (s/valid? ::opts opts)
      (throw (ex-info "Invalid chat options"
                      {:type :invalid-chat-options
                       :errors (s/explain-data ::opts opts)})))
    (validate-auth! opts)))
