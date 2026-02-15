(ns aimee.chat.parser
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; :api-finish-reason values (passthrough from OpenAI API)
;; ---------------------------------------------------------------------------
;; "stop"           - Model finished naturally
;; "length"         - Hit max_tokens limit
;; "content_filter" - Content policy violation
;; "function_call"  - Model called a function (legacy)
;; "tool_calls"     - Model requested tool calls
;; ---------------------------------------------------------------------------

(defn- parse-sse-payload
  [payload]
  (let [object (:object payload)]
    (if (and object (not= object "chat.completion.chunk"))
      {:skip? true}
      (let [delta (get-in payload [:choices 0 :delta])
            content (get delta :content "")
            refusal (get delta :refusal)
            refusal? (when (and (string? refusal) (not (str/blank? refusal))) true)
            content (if (and (empty? content) refusal?) refusal content)
            role (get delta :role)
            tool-calls (get delta :tool_calls)
            function-call (get delta :function_call)
            api-finish-reason (get-in payload [:choices 0 :finish_reason])
            usage (:usage payload)
            base {:content content
                  :role role
                  :tool-calls tool-calls
                  :function-call function-call
                  :api-finish-reason api-finish-reason
                  :usage usage
                  :done? (some? api-finish-reason)}]
        (cond-> base
          refusal? (assoc :refusal refusal
                          :refusal? true))))))

(defn- parse-sse-data-with
  [data parse-payload-fn]
  (cond
    (nil? data) {:content "" :api-finish-reason nil}
    (= data "[DONE]") {:done? true}
    :else
    (parse-sse-payload (parse-payload-fn data))))

(defn parse-sse-event
  "Parse OpenAI SSE event data.

  Returns {:content :refusal :refusal? :api-finish-reason :done?}.
  Returns {:done? true} for [DONE] sentinel.
  Returns {:skip? true} for non-chat.completion.chunk payloads.

  Refusal text is normalized into :content when content is blank.
  "
  [data]
  (parse-sse-data-with
   data
   (fn [raw]
     (try
       (json/parse-string raw true)
       (catch Exception _ nil)))))

(defn parse-sse-event!
  "Parse OpenAI SSE event data, throwing on parse error.

  Returns {:content :refusal :refusal? :api-finish-reason :done?}.
  Returns {:done? true} for [DONE] sentinel.
  Returns {:skip? true} for non-chat.completion.chunk payloads.
  Throws Exception if JSON parsing fails.

  Refusal text is normalized into :content when content is blank.
  "
  [data]
  (parse-sse-data-with data #(json/parse-string % true)))

(defn parse-final-response
  "Parse a non-streaming chat response body.

  Returns {:content :api-finish-reason :role :tool-calls :function-call :usage :refusal :refusal?}.
  Refusal text is normalized into :content when content is blank.
  "
  [body]
  (let [payload (json/parse-string body true)
        refusal (get-in payload [:choices 0 :message :refusal])
        refusal? (when (and (string? refusal) (not (str/blank? refusal))) true)
        content (get-in payload [:choices 0 :message :content] "")
        content (if (and (empty? content) refusal?) refusal content)
        base {:content content
              :role (get-in payload [:choices 0 :message :role])
              :tool-calls (get-in payload [:choices 0 :message :tool_calls])
              :function-call (get-in payload [:choices 0 :message :function_call])
              :api-finish-reason (get-in payload [:choices 0 :finish_reason])
              :usage (:usage payload)}]
    (cond-> base
      refusal? (assoc :refusal refusal
                      :refusal? true))))

(defn- normalize-acc
  [acc]
  (if (map? acc) acc {:content (or acc "")}))

(defn- event-fields
  [event]
  (if-let [parsed (:parsed event)]
    parsed
    (parse-sse-event (:data event))))

(defn- merge-metadata
  [acc-map {:keys [role tool-calls function-call api-finish-reason usage refusal refusal?]}]
  (cond-> acc-map
    role (assoc :role role)
    tool-calls (assoc :tool-calls tool-calls)
    function-call (assoc :function-call function-call)
    api-finish-reason (assoc :api-finish-reason api-finish-reason)
    usage (assoc :usage usage)
    refusal (update :refusal (fnil str "") refusal)
    refusal? (assoc :refusal? true)))

(defn- append-content
  [acc-map content]
  (if (and (string? content) (empty? content))
    acc-map
    (update acc-map :content (fnil str "") content)))

(defn accumulate-content
  "Append delta content to the accumulator map.

  Handles the :done? sentinel by returning acc unchanged.

  Uses already-parsed data from :parsed key if available (included by default
  in chunk events). Otherwise falls back to parsing the :data field.
  "
  [acc event]
  (let [acc-map (normalize-acc acc)
        {:keys [content done? skip?] :as fields} (event-fields event)]
    (cond
      skip?
      acc-map

      done?
      (merge-metadata acc-map fields)

      :else
      (-> acc-map
          (append-content content)
          (merge-metadata fields)))))

(defn accumulate-metadata
  "Accumulate metadata without appending content."
  [acc event]
  (let [acc-map (normalize-acc acc)
        {:keys [skip?] :as fields} (event-fields event)]
    (if skip?
      acc-map
      (merge-metadata acc-map fields))))
