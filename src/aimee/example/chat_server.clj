(ns aimee.example.chat-server
  "A minimal example showing how to use Aimee for streaming chat responses.

   The key concepts:
   1. Create a core.async channel for SSE frames
   2. Call chat/start-request! with an event-channel
   3. Read events from event-channel, format as SSE, write to frame-channel
   4. Return frame-channel as the HTTP response body (Jetty streams it to client)

   Run: clojure -M -m aimee.example.chat-server
   REPL: (start-server!) (stop-server!)
   Test: curl -X POST http://localhost:8080/chat -H 'Content-Type: application/json' -d '{\"messages\":[{\"role\":\"user\",\"text\":\"hello\"}]}'
  "
  (:require [aimee.chat.client :as chat]
            [aimee.chat.sse-helpers :as sse-helpers]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [ring.adapter.jetty9 :as jetty]
            [ring.core.protocols :as protocols])
  (:import (clojure.core.async.impl.channels ManyToManyChannel)))

(defonce server* (atom nil))

;; Step 1: Tell Ring/Jetty how to stream a core.async channel to the HTTP response.
;; When the response body is a ManyToManyChannel, this writes each frame to the output.
(extend-protocol protocols/StreamableResponseBody
  ManyToManyChannel
  (write-body-to-stream [ch _response output-stream]
    (with-open [writer (io/writer output-stream)]
      (loop []
        (when-let [frame (async/<!! ch)]
          (.write writer frame)
          (.flush writer)
          (recur))))))

;; Step 2: Convert incoming HTTP request body to a user message string.
;; This handles Deep Chat format: {\"messages\": [{\"role\": \"user\", \"text\": \"...\"}]}
(defn- extract-user-message
  [request]
  (let [body (slurp (:body request))]
    (if (empty? body)
      ""
      (let [payload (json/parse-string body true)]
        (or (->> (:messages payload)
                 (filter #(= "user" (:role %)))
                 (map #(:text %))
                 last)
            "")))))

;; Step 3: Build options map for chat/start-request!
;; The :channel key is where Aimee will write events.
(defn- make-chat-opts
  [user-message event-channel]
  {:url (or (System/getenv "OPENAI_API_URL")
            "https://api.openai.com/v1/chat/completions")
   :api-key (System/getenv "OPENAI_API_KEY")
   :model "gpt-4o-mini"
   :stream? true
   :channel event-channel
   :messages [{:role "user" :content user-message}]})

;; Step 4: Start a background thread that:
;;   - Calls chat/start-request! to begin the OpenAI stream
;;   - Reads events from event-channel
;;   - Formats each event as SSE and writes to frame-channel
;;   - Closes frame-channel when done
(defn- start-chat-stream!
  [user-message frame-channel]
  (async/thread
    (try
      (async/>!! frame-channel ": stream-open\n\n")
      (if (empty? user-message)
        (do
          (async/>!! frame-channel (sse-helpers/format-sse-data {:text "Error: No message provided"}))
          (async/>!! frame-channel (sse-helpers/format-sse-done)))
        (let [event-channel (async/chan 128)]
          (chat/start-request! (make-chat-opts user-message event-channel))
          (loop []
            (when-let [event (async/<!! event-channel)]
              (case (:event event)
                :chunk
                (do
                  (when-let [frame (sse-helpers/event->simplified-sse event)]
                    (async/>!! frame-channel frame))
                  (recur))
                :complete
                (async/>!! frame-channel (sse-helpers/format-sse-done))
                :error
                (do
                  (async/>!! frame-channel (sse-helpers/format-sse-data {:text (str "Error: " (:data event))}))
                  (async/>!! frame-channel (sse-helpers/format-sse-done))))))))
      (finally
        (async/close! frame-channel)))))

;; Step 5: HTTP handler - creates frame-channel, starts stream, returns SSE response
(defn- handle-chat
  [request]
  (let [frame-channel (async/chan 256)
        user-message (extract-user-message request)]
    (start-chat-stream! user-message frame-channel)
    {:status 200
     :headers {"content-type" "text/event-stream; charset=utf-8"
               "cache-control" "no-cache"}
     :body frame-channel}))

(defn app
  ([request]
   (if (and (= :post (:request-method request))
            (= "/chat" (:uri request)))
     (handle-chat request)
     {:status 404 :body "Not found"}))
  ([request respond _raise]
   (respond (app request))))

(defn stop-server!
  []
  (when-let [server @server*]
    (.stop server)
    (reset! server* nil))
  :stopped)

(defn start-server!
  ([] (start-server! 8080))
  ([port]
   (stop-server!)
   (let [server (jetty/run-jetty #'app {:port port :join? false :async? true})]
     (reset! server* server)
     (println "Server running on http://localhost:" port "/chat")
     server)))

(defn -main
  [& _]
  (start-server! 8080)
  (while true (Thread/sleep 1000)))

(comment
  (start-server! 8080)
  (stop-server!))
