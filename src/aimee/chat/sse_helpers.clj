(ns aimee.chat.sse-helpers
  (:require [aimee.chat.parser :as chat-parser]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn format-sse-data
  "Return an SSE data frame with a JSON payload for the browser client."
  [payload]
  (when-not (map? payload)
    (throw (ex-info "sse payload must be a map" {:payload payload})))
  (str "data: " (json/generate-string payload) "\n\n"))

(defn format-sse-done
  "Return an SSE data frame signaling stream completion."
  []
  "data: [DONE]\n\n")

(defn- extract-content
  "Extract content from an SSE data string.

  - Returns {:done? true} for [DONE].
  - If the data looks like OpenAI JSON, extracts choices[0].delta.content.
  - Otherwise returns the raw data as content.
  "
  [data]
  (cond
    (nil? data) {:content ""}
    (or (= data "[DONE]") (str/starts-with? data "{"))
    (let [{:keys [content done? refusal refusal?]} (chat-parser/parse-sse-event data)]
      {:content (or content "")
       :refusal refusal
       :refusal? refusal?
       :done? done?})
    :else {:content data}))

(defn- has-stream-meta?
  [parsed]
  (boolean
   (some some? [(:role parsed)
                (:tool-calls parsed)
                (:function-call parsed)])))

(defn- chunk->payload
  [chunk]
  (let [parsed (or (:parsed chunk) (extract-content (:data chunk)))
        content (or (:content parsed) "")
        has-meta? (has-stream-meta? parsed)]
    (when (or (not (empty? content)) has-meta?)
      (cond-> {:text content}
        (some? (:role parsed)) (assoc :role (:role parsed))
        (some? (:tool-calls parsed)) (assoc :tool-calls (:tool-calls parsed))
        (some? (:function-call parsed)) (assoc :function-call (:function-call parsed))
        (:refusal? parsed) (assoc :refusal? true)))))

(defn event->simplified-sse
  "Convert a channel event into a simplified SSE frame.

  Input contract:
  - Expects channel events of shape {:event <keyword> :data <payload>}.

  Behavior:
  - :chunk events with content -> `data: {\"text\":\"...\"}\\n\\n`
  - :complete events emit DONE only when :data includes :done-event
  - :complete events without :done-event -> nil
  - :error events -> nil
  "
  [channel-event]
  (when (map? channel-event)
    (case (:event channel-event)
      :chunk
      (some-> (:data channel-event)
              chunk->payload
              format-sse-data)

      :complete
      (when (get-in channel-event [:data :done-event])
        (format-sse-done))

      :error
      nil

      nil)))
