(ns aimee.util
  "Internal runtime/logging helpers used by core namespaces.")

(defn thread-info
  "Internal helper for consistent thread diagnostics in logs.

  Returns a map with information about the current thread.
  Requires JDK 21+ for virtual thread support."
  []
  (let [t (Thread/currentThread)]
    {:thread/id (.getId t)
     :thread/virtual? (.isVirtual t)}))
