(ns aimee.http
  (:require [babashka.http-client :as http]))

(defn request
  "Perform an HTTP request.

  Options:
  - :method (keyword)
  - :uri (string)
  - :as (:stream or :string, default :string)
  - :headers (map)
  - :body (string/InputStream)
  - :timeout (ms)
  - :throw (boolean, default false)
  "
  [opts]
  (http/request (merge {:as :string
                        :throw false}
                       opts)))

(defn post
  "POST request."
  [uri opts]
  (request (merge {:method :post :uri uri} opts)))

(comment
  ;; REPL helper
  (def resp
    (post "https://httpbin.org/stream/3" {:as :stream}))
  ;; Check :status and handle non-2xx responses in callers.
  (:status resp)
  (-> resp :body slurp))
