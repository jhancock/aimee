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
  {:stream? false
   ;; When true, SSE streaming is used for the response.
   :parse-chunks? true
   ;; When true, parsed chunk data is attached to each event.
   :accumulate? true
   ;; When true, chunk text is accumulated into :complete content.
   :on-parse-error :stop
   ;; :stop -> error and close; :continue -> warn and skip bad chunk.
   :overflow-max 10000
   ;; Maximum queued events before backpressure/overflow handling.
   :overflow-mode :queue
   ;; :queue buffers events; :block blocks immediately on channel.
   :channel-idle-timeout-ms nil
   ;; Nil disables the idle timeout (scheduler). When set, abort if no events are
   ;; successfully emitted to the channel for this duration.
   ;; Note: enqueuing into the overflow buffer does not count as progress.
   :http-timeout-ms nil
   ;; HTTP request timeout (ms). Nil means no explicit timeout set.
   :api-key-env "OPENAI_API_KEY"
   ;; Environment variable used to resolve :api-key when not provided directly.
   :headers nil
   ;; Additional HTTP headers to merge into the request.
   :include-usage? false
   ;; When true (and :stream? true), request final usage stats in :complete. Commonly, usage data comes though when :stream? is false without setting this option
   :choices-n 1
   ;; Chat completions are fixed to a single choice (choices-n=1).
   })

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
  [{:keys [api-key api-key-fn api-key-env] :as opts}]
  (let [opts (if (non-blank-string? api-key)
               opts
               (dissoc opts :api-key))
        resolved (or (when (non-blank-string? api-key)
                       api-key)
                     (when (ifn? api-key-fn)
                       (let [value (call-api-key-fn api-key-fn opts)]
                         (when (non-blank-string? value)
                           value)))
                     (let [env-name (or api-key-env "OPENAI_API_KEY")
                           value (System/getenv env-name)]
                       (when (non-blank-string? value)
                         value)))]
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
(s/def ::api-key-env non-blank-string?)
(s/def ::model non-blank-string?)
(s/def ::messages (s/and sequential? seq))
(s/def ::stream? boolean?)
(s/def ::parse-chunks? boolean?)
(s/def ::accumulate? boolean?)
(s/def ::on-parse-error #{:stop :continue})
(s/def ::overflow-max pos-int?)
(s/def ::overflow-mode #{:queue :block})
(s/def ::channel-idle-timeout-ms (s/nilable nat-int?))
(s/def ::http-timeout-ms (s/nilable nat-int?))
(s/def ::headers (s/nilable map?))

(s/def ::include-usage? boolean?)

(s/def ::choices-n #{1})

(s/def ::opts
  (s/keys :req-un [::channel ::url ::model ::messages]
          :opt-un [::stream?
                   ::api-key
                   ::api-key-fn
                   ::api-key-env
                   ::parse-chunks?
                   ::accumulate?
                   ::on-parse-error
                   ::overflow-max
                   ::overflow-mode
                   ::channel-idle-timeout-ms
                   ::http-timeout-ms
                   ::headers
                   ::include-usage?
                   ::choices-n]))

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
