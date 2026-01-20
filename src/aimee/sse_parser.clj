(ns aimee.sse-parser
  (:require [clojure.string :as str]))

(defn parse-line [line]
  (cond
    (nil? line) {:kind :eof}
    (empty? line) {:kind :blank}
    (str/starts-with? line ":") {:kind :comment :raw line}
    :else
    (let [idx (str/index-of line ":")]
      (if (neg? idx)
        {:kind :unknown :raw line}
        (let [field (subs line 0 idx)
              value (subs line (inc idx))
              value (if (str/starts-with? value " ") (subs value 1) value)]
          {:kind :field
           :field field
           :value value
           :raw line})))))

(defn empty-state []
  {:data-lines []
   :raw-lines []
   :event-type nil
   :event-id nil
   :last-id nil})

(defn- reset-event [state]
  (assoc state :data-lines [] :raw-lines [] :event-type nil :event-id nil))

(defn- build-event [state capture-raw?]
  (when (seq (:data-lines state))
    (cond-> {:id (or (:event-id state) (:last-id state))
             :type (:event-type state)
             :data (str/join "\n" (:data-lines state))}
      capture-raw? (assoc :raw {:lines (:raw-lines state)}))))

(defn finish-event [state opts]
  (let [event (build-event state (:capture-raw? opts))
        next-state (-> state
                       (assoc :last-id (or (:event-id state) (:last-id state)))
                       (reset-event))]
    [event next-state]))

(defn step
  "Process a parsed SSE line and return updated state/acc and actions.

  Returns a map with:
  - :state (updated parser state)
  - :acc (updated accumulator string)
  - :event (event map or nil)
  - :flush (:blank-line or :eof or nil)
  - :complete (:done or :eof or nil)
  - :unrecognized? (true when line/field is unrecognized)
  "
  [state acc parsed opts]
  (let [{:keys [capture-raw? emit-done? accumulator]} opts
        update-raw (fn [st]
                     (if capture-raw?
                       (update st :raw-lines conj (:raw parsed))
                       st))]
    (case (:kind parsed)
      :eof
      (let [[event next-state] (finish-event state opts)
            acc (if (and event accumulator)
                  (accumulator acc event)
                  acc)]
        {:state next-state
         :acc acc
         :event event
         :flush :eof
         :complete :eof
         :boundary-lines (:data-lines state)})

      :blank
      (let [[event next-state] (finish-event state opts)
            done? (and event (= "[DONE]" (:data event)))
            done-event (when done? event)
            event (if (and done? (not emit-done?)) nil event)
            acc (if (and event accumulator (not done?))
                  (accumulator acc event)
                  acc)]
        {:state next-state
         :acc acc
         :event event
         :done-event done-event
         :flush :blank-line
         :complete (when done? :done)
         :boundary-lines (:data-lines state)})

      :comment
      {:state (update-raw state)
       :acc acc}

      :unknown
      {:state (update-raw state)
       :acc acc
       :unrecognized? true}

      :field
      (let [{:keys [field value raw]} parsed
            state (cond-> state
                    capture-raw? (update :raw-lines conj raw))]
        (case field
          "data" {:state (update state :data-lines conj value)
                  :acc acc}
          "id" {:state (assoc state :event-id (when (seq value) value))
                :acc acc}
          "event" {:state (assoc state :event-type (when (seq value) value))
                   :acc acc}
          "retry" {:state state
                   :acc acc}
          {:state state
           :acc acc
           :unrecognized? true}))

      {:state state
       :acc acc})))
