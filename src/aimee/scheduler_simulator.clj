(ns aimee.scheduler-simulator
  (:require [aimee.scheduler :as scheduler]))

(defn run-shutdown-smoke!
  "Schedule a short-lived task and wait for scheduler shutdown.

  Preconditions:
  - Run this when scheduler is idle (:timers 0, no active executor), unless
    :require-idle? is false.

  Options:
  - :require-idle? (default true)

  Returns a map with status snapshots."
  ([] (run-shutdown-smoke! {}))
  ([{:keys [require-idle?] :or {require-idle? true}}]
   (let [before (scheduler/status)]
     (when (and require-idle?
                (or (pos? (:timers before))
                    (:executor? before)))
       (throw (ex-info "Scheduler smoke test requires idle scheduler"
                       {:type :scheduler-not-idle
                        :before before
                        :hint "Cancel active timers first or call with {:require-idle? false}"})))
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
             {:before before
              :ticks @ticks
              :after-cancel after-cancel
              :after-shutdown (scheduler/status)})))))))

(comment
  ;; Scheduler lifecycle smoke test (no network)
  (run-shutdown-smoke!))
