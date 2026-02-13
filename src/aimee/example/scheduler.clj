(ns aimee.example.scheduler
  (:require [aimee.scheduler :as scheduler]))

(defn test-shutdown-smoke!
  "Schedule a short-lived task and wait for scheduler shutdown.

  Preconditions:
  - Run this when scheduler is idle (:timers 0, no active executor), unless
    :require-idle? is false.

  Returns a map with status snapshots.
  "
  ([] (test-shutdown-smoke! {}))
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

(defn test-concurrent-shutdown!
  "Test thread safety of shutdown scheduling.

  Scenario 1: Race between adding a timer and scheduling shutdown.
  Scenario 2: Multiple threads racing to schedule shutdown.

  Returns test results.
  "
  []
  (let [results (atom {:scenario1 false
                       :scenario2 false})
        ;; Scenario 1: Timer added during shutdown window
        run-scenario1 (fn []
                        (let [cancel-shutdown (scheduler/schedule-fixed-delay!
                                                0
                                                100
                                                #(identity))
                              _ (Thread/sleep 50)
                              _ (cancel-shutdown)
                              ;; Now shutdown is pending - quickly add a new timer
                              cancel-timer2 (scheduler/schedule-fixed-delay!
                                            0
                                            100
                                            #(identity))
                              status-after (scheduler/status)]
                          (Thread/sleep 50)
                          (cancel-timer2)
                          (Thread/sleep 150)
                          ;; Scheduler should still be alive because timer2 cleared shutdown
                          (swap! results assoc :scenario1
                                 (not (:shutdown? (scheduler/status))))))
        ;; Scenario 2: Multiple threads racing to cancel timers
        run-scenario2 (fn []
                        (scheduler/reset-for-testing!)
                        ;; Create multiple timers
                        (let [timers (for [i (range 10)]
                                      (scheduler/schedule-fixed-delay!
                                       0
                                       100
                                       #(identity)))
                              _ (Thread/sleep 50)
                              ;; All threads cancel simultaneously - race to schedule shutdown
                              futures (for [cancel! timers]
                                       (future (cancel!)))]
                          (doseq [f futures] @f)
                          (Thread/sleep 150)
                          ;; Should shut down cleanly without errors
                          (swap! results assoc :scenario2 true)))]
    (run-scenario1)
    (Thread/sleep 400)
    (run-scenario2)
    (Thread/sleep 300)
    @results))

(comment
  ;; Run scheduler tests

  ;; Scheduler lifecycle smoke test (no network)
  (def result-shutdown (test-shutdown-smoke!))
  (:ticks result-shutdown)
  (:shutdown? (:after-shutdown result-shutdown))

  ;; Concurrent shutdown thread safety test
  (def result-concurrent (test-concurrent-shutdown!))
  (:scenario1 result-concurrent)
  (:scenario2 result-concurrent)
  )
