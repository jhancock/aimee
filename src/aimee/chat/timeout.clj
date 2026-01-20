(ns aimee.chat.timeout
  (:require [aimee.chat.events :as events]
            [aimee.scheduler :as scheduler]))

(defn start-idle-timeout!
  "Start idle-timeout checks. Returns a cancel function or nil."
  [{:keys [channel-idle-timeout-ms last-progress terminated? emit! stop-fn]}]
  (when channel-idle-timeout-ms
    (let [delay-ms (long (min 1000 channel-idle-timeout-ms))
          cancel-timeout! (atom nil)]
      (reset! cancel-timeout!
              (scheduler/schedule-fixed-delay!
               delay-ms
               delay-ms
               (fn []
                 (cond
                   @terminated?
                   (when-let [cancel @cancel-timeout!]
                     (cancel))

                   :else
                   (let [elapsed-ms (/ (double (- (System/nanoTime) @last-progress)) 1000000.0)]
                     (when (> elapsed-ms channel-idle-timeout-ms)
                       (reset! terminated? true)
                       (emit! (events/make-event :complete {:content "" :reason :timeout}) true)
                       (stop-fn)
                       (when-let [cancel @cancel-timeout!]
                         (cancel))))))))
      @cancel-timeout!)))
