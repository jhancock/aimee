(ns aimee.example.chat-server
  "A minimal example showing how to use Aimee for streaming chat responses.

   The key concepts:
   1. Create a core.async channel for SSE frames
   2. Call chat/start-request! with an event-channel
   3. Read events from event-channel, format as SSE, write to frame-channel
   4. Return frame-channel as the HTTP response body (Jetty streams it to client)

   Run: clojure -M:chat-server -m aimee.example.chat-server
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
  (:import (clojure.core.async.impl.channels ManyToManyChannel)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (java.util.concurrent Executors)))

(defonce server* (atom nil))

(extend-protocol protocols/StreamableResponseBody
  ManyToManyChannel
  (write-body-to-stream [ch _response output-stream]
    (with-open [writer (io/writer output-stream)]
      (loop []
        (when-let [frame (async/<!! ch)]
          (.write writer frame)
          (.flush writer)
          (recur))))))

(defn- extract-user-message
  [request]
  (let [body (slurp (:body request))]
    (if (empty? body)
      ""
      (let [payload (json/parse-string body true)]
        (or (->> (:messages payload)
                 (filter #(= "user" (:role %)))
                 (map :text)
                 last)
            "")))))

(defn- make-chat-opts
  [user-message event-channel]
  {:url (or (System/getenv "OPENAI_API_URL")
            "https://api.openai.com/v1/chat/completions")
   :api-key (System/getenv "OPENAI_API_KEY")
   :model "gpt-5-mini"
   :stream? true
   :channel event-channel
   :messages [{:role "user" :content user-message}]})

(defn- start-chat-stream!
  [user-message frame-channel]
  (async/thread
    (try
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

(defn- handle-chat
  [request]
  (let [frame-channel (async/chan 256)
        user-message (extract-user-message request)]
    (start-chat-stream! user-message frame-channel)
    {:status 200
     :headers {"content-type" "text/event-stream; charset=utf-8"
               "cache-control" "no-cache"}
     :body frame-channel}))

(def ^:private chat-page-html
  "<!DOCTYPE html>
<html lang='en'>
<head>
  <meta charset='UTF-8'>
  <meta name='viewport' content='width=device-width, initial-scale=1.0'>
  <title>Aimee Chat</title>
  <script type='module' src='https://unpkg.com/deep-chat@2.4.2/dist/deepChat.bundle.js'></script>
  <style>
    body { margin: 0; padding: 20px; font-family: system-ui; background: #f5f5f5; }
    .container { max-width: 800px; margin: 0 auto; }
    h1 { color: #333; margin-bottom: 20px; }
    deep-chat { width: 100%; height: 70vh; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
  </style>
</head>
<body>
  <div class='container'>
    <h1>Aimee Chat</h1>
    <deep-chat id='chat'></deep-chat>
  <script>
    const el = document.getElementById('chat');
    el.demo = false;
    el.request = {url: '/chat', stream: true};
    el.introMessage = {text: 'Hello! Ask me anything.'};
    el.textInput = {placeholder: {text: 'Type a message...'}};
  </script>
    </deep-chat>
  </div>
</body>
</html>")

(defn app
  ([request]
   (cond
     (and (= :get (:request-method request))
          (= "/chat" (:uri request)))
     {:status 200
      :headers {"content-type" "text/html; charset=utf-8"}
      :body chat-page-html}

     (and (= :post (:request-method request))
          (= "/chat" (:uri request)))
     (handle-chat request)

     :else
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
   (let [thread-pool (QueuedThreadPool.)]
     (.setVirtualThreadsExecutor thread-pool (Executors/newVirtualThreadPerTaskExecutor))
     (let [server (jetty/run-jetty #'app
                                   {:port port
                                    :thread-pool thread-pool
                                    :join? false
                                    :send-server-version? false})]
       (reset! server* server)
       (println (str "Server running on http://localhost:" port "/chat"))
       server))))

(defn -main
  [& _]
  (start-server! 8080)
  (while true (Thread/sleep 1000)))

(comment
  (start-server! 8080)
  (stop-server!))
