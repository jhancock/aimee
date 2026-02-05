(ns aimee.chat.parser
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn parse-sse-event
  "Parse OpenAI SSE event data.

  Returns {:content :refusal :refusal? :finish-reason :done?}.
  Returns {:done? true} for [DONE] sentinel.
  Returns {:skip? true} for non-chat.completion.chunk payloads.

  Refusal text is normalized into :content when content is blank.
  "
  [data]
  (cond
    (nil? data) {:content "" :finish-reason nil}
    (= data "[DONE]") {:done? true}
    :else
    (let [payload (try
                    (json/parse-string data true)
                    (catch Exception _ nil))
          object (:object payload)]
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
              finish-reason (get-in payload [:choices 0 :finish_reason])
              usage (:usage payload)
              base {:content content
                    :role role
                    :tool-calls tool-calls
                    :function-call function-call
                    :finish-reason finish-reason
                    :usage usage
                    :done? (some? finish-reason)}]
          (cond-> base
            refusal? (assoc :refusal refusal
                            :refusal? true)))))))

(defn parse-sse-event!
  "Parse OpenAI SSE event data, throwing on parse error.

  Returns {:content :refusal :refusal? :finish-reason :done?}.
  Returns {:done? true} for [DONE] sentinel.
  Returns {:skip? true} for non-chat.completion.chunk payloads.
  Throws Exception if JSON parsing fails.

  Refusal text is normalized into :content when content is blank.
  "
  [data]
  (cond
    (nil? data) {:content "" :finish-reason nil}
    (= data "[DONE]") {:done? true}
    :else
    (let [payload (json/parse-string data true)
          object (:object payload)]
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
              finish-reason (get-in payload [:choices 0 :finish_reason])
              usage (:usage payload)
              base {:content content
                    :role role
                    :tool-calls tool-calls
                    :function-call function-call
                    :finish-reason finish-reason
                    :usage usage
                    :done? (some? finish-reason)}]
          (cond-> base
            refusal? (assoc :refusal refusal
                            :refusal? true)))))))

(defn parse-final-response
  "Parse a non-streaming chat response body.

  Returns {:content :finish-reason :role :tool-calls :function-call :usage :refusal :refusal?}.
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
              :finish-reason (get-in payload [:choices 0 :finish_reason])
              :usage (:usage payload)}]
    (cond-> base
      refusal? (assoc :refusal refusal
                      :refusal? true))))

(defn accumulate-content
  "Append delta content to the accumulator map.

  Handles the :done? sentinel by returning acc unchanged.

  Uses already-parsed data from :parsed key if available (included by default
  in chunk events). Otherwise falls back to parsing the :data field.
  "
  [acc event]
  (let [acc-map (if (map? acc) acc {:content (or acc "")})
        {:keys [content done? role tool-calls function-call finish-reason usage skip? refusal refusal?]}
        (if-let [parsed (:parsed event)]
          parsed
          (parse-sse-event (:data event)))]
    (cond
      skip?
      acc-map

      done?
      (cond-> acc-map
        role (assoc :role role)
        tool-calls (assoc :tool-calls tool-calls)
        function-call (assoc :function-call function-call)
        finish-reason (assoc :finish-reason finish-reason)
        usage (assoc :usage usage)
        refusal (update :refusal (fnil str "") refusal)
        refusal? (assoc :refusal? true))

      (and (string? content) (empty? content))
      (cond-> acc-map
        role (assoc :role role)
        tool-calls (assoc :tool-calls tool-calls)
        function-call (assoc :function-call function-call)
        finish-reason (assoc :finish-reason finish-reason)
        usage (assoc :usage usage)
        refusal (update :refusal (fnil str "") refusal)
        refusal? (assoc :refusal? true))

      :else
      (cond-> acc-map
        true (update :content (fnil str "") content)
        role (assoc :role role)
        tool-calls (assoc :tool-calls tool-calls)
        function-call (assoc :function-call function-call)
        finish-reason (assoc :finish-reason finish-reason)
        usage (assoc :usage usage)
        refusal (update :refusal (fnil str "") refusal)
        refusal? (assoc :refusal? true)))))

(defn accumulate-metadata
  "Accumulate metadata without appending content."
  [acc event]
  (let [acc-map (if (map? acc) acc {:content (or acc "")})
        {:keys [done? role tool-calls function-call finish-reason usage skip? refusal refusal?]}
        (if-let [parsed (:parsed event)]
          parsed
          (parse-sse-event (:data event)))]
    (cond
      skip?
      acc-map

      done?
      (cond-> acc-map
        role (assoc :role role)
        tool-calls (assoc :tool-calls tool-calls)
        function-call (assoc :function-call function-call)
        finish-reason (assoc :finish-reason finish-reason)
        usage (assoc :usage usage)
        refusal (update :refusal (fnil str "") refusal)
        refusal? (assoc :refusal? true))

      :else
      (cond-> acc-map
        role (assoc :role role)
        tool-calls (assoc :tool-calls tool-calls)
        function-call (assoc :function-call function-call)
        finish-reason (assoc :finish-reason finish-reason)
        usage (assoc :usage usage)
        refusal (update :refusal (fnil str "") refusal)
        refusal? (assoc :refusal? true)))))
