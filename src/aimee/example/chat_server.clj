(ns aimee.example.chat-server
  "A minimal example showing how to use Aimee for streaming chat responses.

   The key concepts:
   1. Create a core.async channel for events
   2. Call chat/start-request! with the event channel
   3. Return event channel as HTTP response body
   4. write-body-to-stream transforms events to SSE and streams to client

   Run: clojure -M:chat-server -m aimee.example.chat-server
   REPL: (start-server!) (stop-server!)
   Test: curl -X POST http://localhost:8080/chat -H 'Content-Type: application/json' -d '{\"messages\":[{\"role\":\"user\",\"text\":\"hello\"}]}'
  "
  (:require [aimee.chat.client :as chat]
            [aimee.chat.sse-helpers :as sse-helpers]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [hiccup2.core :as h]
            [ring.adapter.jetty9 :as jetty]
            [ring.core.protocols :as protocols]
            [aimee.pp :refer [pprint]])
  (:import (clojure.core.async.impl.channels ManyToManyChannel)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (java.util.concurrent Executors)))

(defonce server* (atom nil))

(extend-protocol protocols/StreamableResponseBody
  ManyToManyChannel
  (write-body-to-stream [event-ch _response output-stream]
    (with-open [writer (io/writer output-stream)]
      (loop []
        (when-let [event (async/<!! event-ch)]
          (case (:event event)
            :chunk
            (do
              (when-let [frame (sse-helpers/event->simplified-sse event)]
                (.write writer frame)
                (.flush writer))
              (recur))
            :complete
            (do
              (.write writer (sse-helpers/format-sse-done))
              (.flush writer))
            :error
            (let [err-msg (str "Error: " (:data event))]
              (log/error err-msg)
              (.write writer (sse-helpers/format-sse-data {:text err-msg}))
              (.write writer (sse-helpers/format-sse-done))
              (.flush writer))))))))

(defn- extract-user-message
  [request]
  (let [body (slurp (:body request))]
    (if (empty? body)
      ""
      (try
        (let [payload (json/parse-string body true)]
          (or (->> (:messages payload)
                   (filter #(= "user" (:role %)))
                   (map :text)
                   last)
              ""))
        (catch Exception e
          (log/error e "Failed to parse JSON body")
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

(defn- chat-page []
  (str "<!DOCTYPE html>\n"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "UTF-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
          [:title "Aimee Chat"]
          [:script {:type "module" :src "https://unpkg.com/deep-chat@2.4.2/dist/deepChat.bundle.js"}]
          [:style "
body { margin: 0; padding: 20px; font-family: system-ui; background: #f5f5f5; }
.container { max-width: 800px; margin: 0 auto; }
h1 { color: #333; margin-bottom: 20px; }
deep-chat { width: 100%; height: 70vh; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }"]]
         [:body
          [:div.container
           [:h1 "Aimee Chat"]
           [:deep-chat {:id "chat"}]
           [:script (h/raw "
customElements.whenDefined('deep-chat').then(() => {
  const el = document.getElementById('chat');
  el.demo = false;
  el.request = {url: '/chat', stream: true};
  el.introMessage = {text: 'Hello! Ask me anything.'};
  el.textInput = {placeholder: {text: 'Type a message...'}};
});")]]]])))

(defn- handle-chat-page
  [_request]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body (chat-page)})

(defn- handle-chat-stream
  [request]
  (let [body (slurp (:body request))]
    (log/info "Deep Chat request body:" body)
    (let [event-channel (async/chan 128)
          user-message (extract-user-message {:body (java.io.ByteArrayInputStream. (.getBytes body))})]
    (if (empty? user-message)
      (do
        (async/>!! event-channel {:event :error :data "No message provided"})
        (async/close! event-channel))
      (chat/start-request! (make-chat-opts user-message event-channel)))
    {:status 200
     :headers {"content-type" "text/event-stream; charset=utf-8"
               "cache-control" "no-cache"}
     :body event-channel})))

(defn- handle-simple-stream
  [request]
  (let [event-channel (async/chan 128)
        user-message (str (.trim (slurp (:body request))))]
    (if (empty? user-message)
      (do
        (async/>!! event-channel {:event :error :data "No message provided"})
        (async/close! event-channel))
      (chat/start-request! (make-chat-opts user-message event-channel)))
    {:status 200
     :headers {"content-type" "text/event-stream; charset=utf-8"
               "cache-control" "no-cache"}
     :body event-channel}))

(defroutes app
  (GET "/chat" [] handle-chat-page)
  (POST "/chat" [] handle-chat-stream)
  (POST "/chat/simple" [] handle-simple-stream)
  (route/not-found "Not found"))

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
