(ns aimee.example.lifecycle
  (:require [aimee.chat.emitter :as emitter]
            [clojure.core.async :as async]))

(comment
  ;; ---------------------------------------------------------------------------
  ;; Lifecycle module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------
  ;;
  ;; Demonstrates channel lifecycle: events flow in, terminal event closes channel.
  ;; All examples are local (no network) for fast REPL feedback.
  ;;
  ;; ---------------------------------------------------------------------------

  ;; Example 1: Local channel smoke test (no network)
  ;; Basic channel lifecycle test with manual event emission.
  (def local-ch (async/chan 3))
  (def local-chunks (atom []))
  (def local-complete (atom nil))
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

        (println "unknown event" event))
      (recur)))
  @local-chunks
  @local-complete

  ;; Example 2: Channel lifecycle guarantee on :complete (local, no network)
  ;; Terminal event is delivered, then channel closes.
  (def lifecycle-ch-complete (async/chan 1))
  (def lifecycle-cb-complete
    (emitter/make-channel-callbacks
     lifecycle-ch-complete
     {:overflow-max 10 :overflow-mode :queue}))
  ((:complete! lifecycle-cb-complete) {:content "done"})
  (def lifecycle-complete-event (async/<!! lifecycle-ch-complete))
  lifecycle-complete-event
  (async/<!! lifecycle-ch-complete)

  ;; Example 3: Channel lifecycle guarantee on :error (local, no network)
  ;; Terminal error is delivered, then channel closes.
  (def lifecycle-ch-error (async/chan 1))
  (def lifecycle-cb-error
    (emitter/make-channel-callbacks
     lifecycle-ch-error
     {:overflow-max 10 :overflow-mode :queue}))
  ((:error! lifecycle-cb-error) (ex-info "simulated failure" {:type :simulated}))
  (def lifecycle-error-event (async/<!! lifecycle-ch-error))
  lifecycle-error-event
  (async/<!! lifecycle-ch-error)
  )
