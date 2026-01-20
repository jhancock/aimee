(ns aimee.scheduler-simulator
  (:require [aimee.scheduler :as scheduler]))

(defn run-shutdown-smoke!
  "Schedule a short-lived task and wait for the scheduler to shut down.

  Returns a map with status snapshots."
  []
  (let [ticks (atom 0)]
    (binding [scheduler/*shutdown-idle-ms* 200]
      (let [cancel! (scheduler/schedule-fixed-delay!
                     0
                     50
                     #(swap! ticks inc))]
        (Thread/sleep 120)
        (cancel!)
        (let [after-cancel (scheduler/status)]
          (Thread/sleep 300)
          {:ticks @ticks
           :after-cancel after-cancel
           :after-shutdown (scheduler/status)})))))

(comment
  ;; Scheduler lifecycle smoke test (no network)
  (run-shutdown-smoke!)
  )
