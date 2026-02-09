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

(defn event->simplified-sse
  "Helper: convert a channel stream event into a simplified SSE data frame.

  Returns a DONE frame for completion items that include a [DONE] payload,
  and nil for empty content or non-streaming completion.
  "
  [item]
  (let [event (cond
                (and (map? item) (= :chunk (:event item))) (:data item)
                (and (map? item) (= :complete (:event item))) (or (get-in item [:data :done-event])
                                                                  (:data item))
                (and (map? item) (= :error (:event item))) nil
                :else (or (:done-event item) item))]
    (when event
      (let [parsed (:parsed event)
            {:keys [content done? refusal?]} (if parsed
                                               parsed
                                               (extract-content (:data event)))
            has-meta? (and parsed (or (some? (:role parsed))
                                      (some? (:tool-calls parsed))
                                      (some? (:function-call parsed))))
            content (or content "")]
        (cond
          (and done? (empty? content) (not has-meta?)) (format-sse-done)
          (and (empty? content) (not has-meta?)) nil
          :else (format-sse-data
                 (cond-> {:text content}
                   (some? (:role parsed)) (assoc :role (:role parsed))
                   (some? (:tool-calls parsed)) (assoc :tool-calls (:tool-calls parsed))
                   (some? (:function-call parsed)) (assoc :function-call (:function-call parsed))
                   refusal? (assoc :refusal? true))))))))
