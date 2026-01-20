(ns aimee.util)

(defn thread-info
  "Return a map with information about the current thread.

  Requires JDK 21+ for virtual thread support."
  []
  (let [t (Thread/currentThread)]
    {:thread/id (.getId t)
     :thread/virtual? (.isVirtual t)}))
