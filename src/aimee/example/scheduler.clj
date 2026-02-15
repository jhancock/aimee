(ns aimee.example.scheduler
  (:require [aimee.scheduler :as scheduler]))

(comment
  ;; ---------------------------------------------------------------------------
  ;; Scheduler module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------
  ;;
  ;; The scheduler manages background timers for idle-timeout detection.
  ;; It auto-starts when first timer is scheduled and auto-shuts down after
  ;; a configurable idle period (default 60s, bound via *shutdown-idle-ms*).
  ;;
  ;; ---------------------------------------------------------------------------

  ;; Example 1: Scheduler lifecycle smoke test (no network)
  ;; Schedule a short-lived task and wait for scheduler shutdown.
  ;; Preconditions: scheduler should be idle (:timers 0, no active executor)
  (def before-status (scheduler/status))
  before-status
  (def ticks-smoke (atom 0))
  (binding [scheduler/*shutdown-idle-ms* 200]
    (def cancel-smoke!
      (scheduler/schedule-fixed-delay!
       0
       50
       #(swap! ticks-smoke inc)))
    (Thread/sleep 120)
    (cancel-smoke!)
    (def after-cancel-status (scheduler/status))
    (Thread/sleep 300)
    (def after-shutdown-status (scheduler/status)))
  @ticks-smoke
  after-cancel-status
  (:shutdown? after-shutdown-status)

  ;; Example: Concurrent shutdown thread safety test
  ;; Scenario 1: Race between adding a timer and scheduling shutdown.
  ;; Scenario 2: Multiple threads racing to schedule shutdown.
  (def concurrent-results (atom {:scenario1 false :scenario2 false}))

  ;; Scenario 1: Timer added during shutdown window
  (def cancel-shutdown-1 (scheduler/schedule-fixed-delay! 0 100 (fn [])))
  (Thread/sleep 50)
  (cancel-shutdown-1)
  ;; Now shutdown is pending - quickly add a new timer
  (def cancel-timer2 (scheduler/schedule-fixed-delay! 0 100 (fn [])))
  (Thread/sleep 50)
  (cancel-timer2)
  (Thread/sleep 150)
  ;; Scheduler should still be alive because timer2 cleared shutdown
  (swap! concurrent-results assoc :scenario1 (not (:shutdown? (scheduler/status))))

  ;; Scenario 2: Multiple threads racing to cancel timers
  (scheduler/reset-for-testing!)
  (def timers-concurrent
    (for [_ (range 10)]
      (scheduler/schedule-fixed-delay! 0 100 (fn []))))
  (Thread/sleep 50)
  ;; All threads cancel simultaneously - race to schedule shutdown
  (def futures-cancel (for [cancel! timers-concurrent]
                        (future (cancel!))))
  (doseq [f futures-cancel] @f)
  (Thread/sleep 150)
  ;; Should shut down cleanly without errors
  (swap! concurrent-results assoc :scenario2 true)
  @concurrent-results
  )
