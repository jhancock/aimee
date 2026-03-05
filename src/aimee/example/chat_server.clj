(ns aimee.example.chat-server
  "A minimal example showing how to use Aimee for streaming chat responses.

   The key concepts:
   1. Create a core.async channel for events
   2. Call aimee.chat/start-request! with the event channel
   3. Use aimee.ring/->ring-stream to create a Ring response body
   4. ->ring-stream handles SSE formatting and allows event hooks

   REPL: (start-server!) 
   Test:
   open browser to http://localhost:8080/chat
   curl -X POST http://localhost:8080/chat -H 'Content-Type: application/json' -d '{\"messages\":[{\"role\":\"user\",\"text\":\"hello\"}]}'
  "
  (:require [aimee.chat.client :as chat]
            [aimee.chat.ring :as ring]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [hiccup2.core :as h]
            [ring.adapter.jetty9 :as jetty])
  (:import (org.eclipse.jetty.util.thread QueuedThreadPool)
           (java.util.concurrent Executors)))

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
          (println "Failed to parse JSON body:" (.getMessage e))
          "")))))

(defn- handle-chat-stream
  [request]
  (let [event-channel (async/chan 128)
        user-message (extract-user-message request)]
    (chat/start-request!
      {:url (or (System/getenv "OPENAI_API_URL")
                "https://api.openai.com/v1/chat/completions")
       :api-key (System/getenv "OPENAI_API_KEY")
       :model "gpt-5-mini"
       :stream? true
       :channel event-channel
       :messages [{:role "user" :content user-message}]})
    {:status 200
     :headers {"content-type" "text/event-stream; charset=utf-8"
               "cache-control" "no-cache"}
     :body (ring/->ring-stream event-channel
               {:on-chunk (fn [e]
                            (println "Chunk:" (get-in e [:data :parsed :content]))
                            e)
                :on-complete (fn [e]
                               (println "Complete:" (:data e))
                               e)})}))

(defroutes app
  (GET "/chat" [] handle-chat-page)
  (POST "/chat" [] handle-chat-stream)
  (route/not-found "Not found"))

(defonce server* (atom nil))

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
