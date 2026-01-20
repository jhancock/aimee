(ns aimee.chat.emitter
  (:require [aimee.chat.events :as events]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defn- ensure-overflow-opts
  [overflow-max overflow-mode]
  (when (nil? overflow-max)
    (throw (ex-info ":overflow-max is required" {:type :missing-overflow-max})))
  (when (nil? overflow-mode)
    (throw (ex-info ":overflow-mode is required" {:type :missing-overflow-mode}))))

(defn- emit-event!
  [channel opts event close?]
  (let [{:keys [overflow-max overflow-mode queue last-progress start-drain!]} opts]
    (ensure-overflow-opts overflow-max overflow-mode)
    (if-let [q @queue]
      ;; Queue exists - route to drain thread
      (do
        (.put q {:event event :close? close?})
        true)
      ;; No queue - try direct write
      (cond
        ;; Channel has capacity
        (async/offer! channel event)
        (do
          (reset! last-progress (System/nanoTime))
          (when close?
            (async/close! channel))
          true)

        ;; Channel full, block mode
        (= overflow-mode :block)
        (do
          (log/warn "overflow disabled; applying backpressure")
          (async/>!! channel event)
          (reset! last-progress (System/nanoTime))
          (when close?
            (async/close! channel))
          true)

        ;; Channel full, queue mode - create overflow queue
        :else
        (let [q (java.util.concurrent.LinkedBlockingQueue. overflow-max)]
          (log/warn "overflow queue created"
                    {:overflow-max overflow-max})
          (reset! queue q)
          (start-drain! q)
          (.put q {:event event :close? close?})
          true)))))

(defn make-emitter
  "Create an emitter for delivering event maps to a channel."
  [channel opts]
  (let [{:keys [overflow-max overflow-mode]} opts
        queue (atom nil)
        last-progress (atom (System/nanoTime))
        drain-started? (atom false)
        start-drain! (fn [q]
                       (when (compare-and-set! drain-started? false true)
                         (async/thread
                           (loop []
                             (when-let [{:keys [event close?]} (.take q)]
                               (if (async/>!! channel event)
                                 (do
                                   (reset! last-progress (System/nanoTime))
                                   (if close?
                                     (async/close! channel)
                                     (recur)))
                                 (async/close! channel)))))))
        emit! (fn [event close?]
                (emit-event!
                 channel
                 {:overflow-max overflow-max
                 :overflow-mode overflow-mode
                 :queue queue
                 :last-progress last-progress
                 :start-drain! start-drain!}
                 event
                 close?))]
    (ensure-overflow-opts overflow-max overflow-mode)
    {:last-progress last-progress
     :emit! emit!}))

(defn make-channel-callbacks
  "Create complete! and error! callbacks for a channel.

  Returns map with:
  - :complete! - function to send completion info and close channel
  - :error! - function to send error and close channel
  "
  ([channel] (make-channel-callbacks channel {}))
  ([channel opts]
   (let [{:keys [emit! last-progress]} (make-emitter channel opts)
         terminated? (:terminated? opts)]
     {:emit! emit!
      :last-progress last-progress
      :terminated? terminated?
      :complete! (fn [info]
                   (when terminated?
                     (reset! terminated? true))
                   (emit! (events/make-event :complete info) true))
      :error! (fn [ex]
                (when terminated?
                  (reset! terminated? true))
                (emit! (events/make-event :error ex) true))})))
