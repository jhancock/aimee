(ns aimee.scheduler
  (:import (java.util.concurrent Executors ScheduledExecutorService ScheduledFuture ThreadFactory TimeUnit)))

(def ^:dynamic *shutdown-idle-ms* 60000)

(defonce ^:private scheduler-state
  (atom {:executor nil
         :timers {}
         :shutdown-task nil}))

(defn- make-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable)
        (.setDaemon true)
        (.setName "aimee-scheduler")))))

(defn- ensure-executor! []
  (let [{:keys [executor]} @scheduler-state]
    (if (and executor (not (.isShutdown ^ScheduledExecutorService executor)))
      executor
      (let [created (Executors/newSingleThreadScheduledExecutor (make-thread-factory))]
        (swap! scheduler-state assoc :executor created)
        created))))

(defn- clear-shutdown-task! []
  (when-let [shutdown-task (:shutdown-task @scheduler-state)]
    (.cancel ^ScheduledFuture shutdown-task false)
    (swap! scheduler-state assoc :shutdown-task nil)))

;; Atomic check-and-set to prevent race where a timer is added
;; between checking (empty? timers) and scheduling shutdown.
(defn- schedule-shutdown-if-idle!
  ([]
   (schedule-shutdown-if-idle! *shutdown-idle-ms*))
  ([idle-ms]
   (swap! scheduler-state
          (fn [{:keys [executor timers shutdown-task] :as state}]
            (if (and executor (empty? timers) (nil? shutdown-task))
              (let [task (.schedule
                          ^ScheduledExecutorService executor
                          (fn []
                            (swap! scheduler-state
                                   (fn [{:keys [executor timers] :as inner-state}]
                                     (when (and executor (empty? timers))
                                       (.shutdown ^ScheduledExecutorService executor))
                                     (assoc inner-state :executor nil :shutdown-task nil))))
                          idle-ms
                          TimeUnit/MILLISECONDS)]
                (assoc state :shutdown-task task))
              state)))))

(defn schedule-fixed-delay!
  "Schedule a fixed-delay task on the shared scheduler.

  Returns a cancel function that stops the task and updates scheduler state."
  [initial-delay-ms delay-ms f]
  (let [executor (ensure-executor!)]
    (clear-shutdown-task!)
    (let [task (.scheduleWithFixedDelay
                ^ScheduledExecutorService executor
                ^Runnable f
                initial-delay-ms
                delay-ms
                TimeUnit/MILLISECONDS)
          task-id (str (java.util.UUID/randomUUID))
          cancel! (fn []
                    (when-let [task ^ScheduledFuture (get-in @scheduler-state [:timers task-id])]
                      (.cancel task false))
                    (swap! scheduler-state update :timers dissoc task-id)
                    (schedule-shutdown-if-idle!))]
      (swap! scheduler-state update :timers assoc task-id task)
      cancel!)))

(defn status
  "Return a snapshot of the scheduler state for simulation/debugging."
  []
  (let [{:keys [executor timers shutdown-task]} @scheduler-state]
    {:executor? (some? executor)
     :shutdown? (when executor (.isShutdown ^ScheduledExecutorService executor))
     :timers (count timers)
     :shutdown-task? (some? shutdown-task)}))

(defn reset-for-testing!
  "Reset scheduler state to initial state. For testing only."
  []
  (reset! scheduler-state {:executor nil
                            :timers {}
                            :shutdown-task nil})
  nil)
