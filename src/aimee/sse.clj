(ns aimee.sse
  (:require [aimee.sse-parser :as parser]
            [aimee.util :as util]
            [clojure.tools.logging :as log])
  (:import (java.io BufferedReader InputStream InputStreamReader)))

(defn- normalize-initial-acc
  [accumulator initial-acc]
  (if (and accumulator (not (map? initial-acc)))
    {:content (or initial-acc "")}
    initial-acc))

(defn- build-complete-info
  [acc done-event reason]
  (let [acc-map (if (map? acc) acc {:content (or acc "")})]
    (cond-> {:content (or (:content acc-map) "")}
      (:finish-reason acc-map) (assoc :finish-reason (:finish-reason acc-map))
      (:role acc-map) (assoc :role (:role acc-map))
      (:tool-calls acc-map) (assoc :tool-calls (:tool-calls acc-map))
      (:function-call acc-map) (assoc :function-call (:function-call acc-map))
      (:usage acc-map) (assoc :usage (:usage acc-map))
      (:refusal acc-map) (assoc :refusal (:refusal acc-map))
      (:refusal? acc-map) (assoc :refusal? true)
      done-event (assoc :done-event done-event)
      reason (assoc :reason reason))))

(defn- read-sse-line
  [^BufferedReader reader]
  (try
    {:line (.readLine reader)}
    (catch Exception ex
      {:error ex})))

(defn- update-last-lines
  [last-lines line]
  (if (seq line)
    (let [lines (if (vector? last-lines) last-lines [])
          lines (conj lines line)]
      (if (> (count lines) 3)
        (subvec lines (- (count lines) 3))
        lines))
    last-lines))

(defn- handle-parsed-line
  [{:keys [state acc line last-lines parsed opts on-event on-line on-flush]}]
  (let [{:keys [sample-rate]} opts
        {:keys [state acc event flush complete unrecognized? boundary-lines done-event]}
        (parser/step state acc parsed opts)]
    (when (and on-line
               (pos? sample-rate)
               (< (rand) sample-rate)
               (some? line))
      (on-line line))
    (when unrecognized?
      (log/warn "sse unrecognized line"
                {:raw (:raw parsed)
                 :last-lines last-lines
                 :thread (util/thread-info)}))
    (when event
      (when on-event
        (on-event event)))
    (when (and on-flush flush)
      (on-flush {:reason flush}))
    {:state state
     :acc acc
     :event event
     :flush flush
     :complete complete
     :boundary-lines boundary-lines
     :done-event done-event}))

(defn- handle-complete
  [{:keys [acc complete done-event boundary-lines event last-lines stop?]}]
  (let [stopped? (and stop? @stop?)
        reason (cond
                 stopped? :stopped
                 (not= complete :done) complete)]
    (when (and (= complete :eof) (not stopped?))
      (log/warn "sse stream ended without done"
                {:had-event (boolean event)
                 :last-lines last-lines
                 :thread (util/thread-info)}))
    (when (and (= complete :eof) (seq boundary-lines))
      (log/warn "sse stream ended without blank line"
                {:boundary-lines (count boundary-lines)
                 :last-lines last-lines
                 :thread (util/thread-info)}))
    (let [info (build-complete-info acc done-event reason)]
      {:info info
       :result {:content (:content info)
                :finish-reason (:finish-reason info)
                :reason reason}})))

(defn consume-sse!
  "Read and parse SSE data from an InputStream. Calls on-complete on completion.

  Options:
  - :on-event (fn [event])
  - :on-complete (fn [{:keys [content reason]}])
  - :on-error (fn [ex])
  - :on-line (fn [line]) ;; sampled line capture
  - :on-flush (fn [{:keys [reason]}])
  - :capture-raw? (default false)
  - :accumulator (fn [acc event] new-acc)
  - :initial-acc (default empty string or {:content \"\"} when accumulator is used)
  - :sample-rate (0.0 - 1.0, default 0.0)
  - :close-input? (default true)
  - :emit-done? (default false)
  "
  [^InputStream input-stream opts]
  (let [{:keys [on-event on-complete on-error on-line on-flush accumulator initial-acc
                sample-rate close-input? emit-done? stop?]}
        (merge {:sample-rate 0.0
                :close-input? true
                :emit-done? false
                :initial-acc ""}
               opts)
        initial-acc (normalize-initial-acc accumulator initial-acc)
        terminated? (:terminated? opts)
        complete! (fn [info]
                    (when (and on-complete (not (and terminated? @terminated?)))
                      (on-complete info)))
        error! (fn [ex]
                 (when (and on-error (not (and terminated? @terminated?)))
                   (on-error ex)))]
    (try
      (when (nil? input-stream)
        (throw (ex-info "input-stream is required"
                        {:type :missing-input-stream})))
      (let [reader (BufferedReader. (InputStreamReader. input-stream "UTF-8"))]
        (loop [state (parser/empty-state)
               acc initial-acc
               last-lines []]
          (let [read-result (read-sse-line reader)]
            (if-let [ex (:error read-result)]
              (do
                (error! ex)
                (let [info (build-complete-info acc nil :error)]
                  {:content (:content info)
                   :finish-reason (:finish-reason info)
                   :reason :error}))
              (let [line (:line read-result)
                    last-lines (update-last-lines last-lines line)
                    parsed (parser/parse-line line)
                    result (handle-parsed-line {:state state
                                                :acc acc
                                                :line line
                                                :last-lines last-lines
                                                :parsed parsed
                                                :opts opts
                                                :on-event on-event
                                                :on-line on-line
                                                :on-flush on-flush})
                    {:keys [state acc event complete boundary-lines done-event]} result]
                (if complete
                  (let [{:keys [info result]} (handle-complete {:acc acc
                                                                :complete complete
                                                                :done-event done-event
                                                                :boundary-lines boundary-lines
                                                                :event event
                                                                :last-lines last-lines
                                                                :stop? stop?})]
                    (complete! info)
                    result)
                  (recur state acc last-lines)))))))
      (catch Exception ex
        (error! ex)
        (let [info (build-complete-info initial-acc nil :error)]
          {:content (:content info)
           :finish-reason (:finish-reason info)
           :reason :error}))
      (finally
        (when close-input?
          (try
            (.close input-stream)
            (catch Exception _)))))))

(comment
  ;; Example: anomaly logging (stream ends without [DONE])
  ;; You should see warn logs with :last-lines showing recent raw input.
  (def sse-incomplete
    (str "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
         "data: {\"choices\":[{\"delta\":{\"content\":\" there\"}}]}\n\n"))
  (def input-incomplete (java.io.ByteArrayInputStream. (.getBytes sse-incomplete "UTF-8")))
  (require '[aimee.chat.parser :as chat-parser])
  (consume-sse! input-incomplete
                {:accumulator chat-parser/accumulate-content
                 :initial-acc ""
                 :on-event (fn [_] nil)}))
