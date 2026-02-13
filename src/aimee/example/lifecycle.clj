(ns aimee.example.lifecycle
  (:require [aimee.chat.emitter :as emitter]
            [clojure.core.async :as async]))

(defn test-local-channel-smoke!
  "Example 0: Local channel smoke test (no network).

  Basic channel lifecycle test with manual event emission."
  []
  (let [local-ch (async/chan 3)
        local-chunks (atom [])
        local-complete (atom nil)]
    (async/>!! local-ch {:event :chunk :data {:data "x"}})
    (async/>!! local-ch {:event :complete :data {:content "done"}})
    (async/close! local-ch)
    (loop []
      (when-let [event (async/<!! local-ch)]
        (case (:event event)
          :chunk
          (swap! local-chunks conj event)

          :complete
          (reset! local-complete event)

          (do
            (println "unknown event" event)))
        (recur)))
    {:chunks @local-chunks
     :complete @local-complete}))

(defn test-channel-lifecycle-complete!
  "Example 5g: Channel lifecycle guarantee on :complete (local, no network).

  Terminal event is delivered, then channel closes."
  []
  (let [lifecycle-ch (async/chan 1)
        callbacks (emitter/make-channel-callbacks
                   lifecycle-ch
                   {:overflow-max 10 :overflow-mode :queue})]
    ((:complete! callbacks) {:content "done"})
    (let [event (async/<!! lifecycle-ch)
          closed? (nil? (async/<!! lifecycle-ch))]
      {:event event
       :channel-closed-after-event? closed?})))

(defn test-channel-lifecycle-error!
  "Example 5h: Channel lifecycle guarantee on :error (local, no network).

  Terminal error is delivered, then channel closes."
  []
  (let [lifecycle-ch (async/chan 1)
        callbacks (emitter/make-channel-callbacks
                   lifecycle-ch
                   {:overflow-max 10 :overflow-mode :queue})]
    ((:error! callbacks) (ex-info "simulated failure" {:type :simulated}))
    (let [event (async/<!! lifecycle-ch)
          closed? (nil? (async/<!! lifecycle-ch))]
      {:event event
       :channel-closed-after-event? closed?})))

(comment
  ;; Run lifecycle tests

  ;; Local channel smoke test
  (def result-0 (test-local-channel-smoke!))
  (count (:chunks result-0))

  ;; Channel lifecycle on :complete
  (def result-5g (test-channel-lifecycle-complete!))
  (:event result-5g)
  (:channel-closed-after-event? result-5g)

  ;; Channel lifecycle on :error
  (def result-5h (test-channel-lifecycle-error!))
  (:event result-5h)
  (:channel-closed-after-event? result-5h)
  )
