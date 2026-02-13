(ns aimee.example.core
  (:require [clojure.core.async :as async]))

(def openai-api-url
  (or (System/getenv "OPENAI_API_URL")
      "https://api.openai.com/v1/chat/completions"))

(def openai-api-key
  (System/getenv "OPENAI_API_KEY"))

(defn await-terminal-event!!
  "Wait for the terminal event (:complete or :error) from a channel.

  Returns the terminal event or nil if timeout occurs."
  [ch timeout-ms]
  (loop [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (let [remaining (- deadline (System/currentTimeMillis))]
      (when (pos? remaining)
        (let [[event port] (async/alts!! [ch (async/timeout remaining)])]
          (cond
            (not= port ch)
            nil

            (nil? event)
            nil

            (#{:complete :error} (:event event))
            event

            :else
            (recur deadline)))))))

(comment
  ;; Example: await terminal event from a channel
  (require '[clojure.core.async :as async])
  (def ch (async/chan 10))
  (async/>!! ch {:event :complete :data {:content "done"}})
  (await-terminal-event!! ch 1000)
  )
