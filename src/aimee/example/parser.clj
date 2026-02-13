(ns aimee.example.parser
  (:require [aimee.sse-parser :as parser]))

(defn test-max-data-lines-limit!
  "Test max-data-lines forcing a flush.

  When the limit is exceeded (trying to add a line to an event that already has
  max-data-lines), the parser:
  - Emits the accumulated event
  - Resets state
  - Continues processing subsequent lines
  "
  []
  (let [max-lines 3
        opts {:max-data-lines max-lines}
        state (parser/empty-state)
        step1 (parser/step state {} (parser/parse-line "data: line1") opts)
        step2 (parser/step (:state step1) {} (parser/parse-line "data: line2") opts)
        step3 (parser/step (:state step2) {} (parser/parse-line "data: line3") opts)
        step4 (parser/step (:state step3) {} (parser/parse-line "data: line4") opts)]
    step4))

(defn test-basic-parsing!
  "Test basic SSE line parsing behavior."
  []
  {:blank-line (parser/parse-line "")
   :comment (parser/parse-line ":comment")
   :data-field (parser/parse-line "data: hello world")
   :id-field (parser/parse-line "id: msg-123")
   :event-field (parser/parse-line "event: message")
   :retry-field (parser/parse-line "retry: 3000")
   :unknown-field (parser/parse-line "unknown: value")
   :no-colon (parser/parse-line "no colon here")
   :eof (parser/parse-line nil)})

(defn test-edge-cases!
  "Test edge cases in SSE parsing."
  []
  {:value-with-leading-space (parser/parse-line "data:  leading space")
   :value-without-leading-space (parser/parse-line "data:no leading space")
   :multiple-colons (parser/parse-line "data: key:value")
   :empty-value (parser/parse-line "data: ")
   :multiple-blank-lines (map parser/parse-line ["" "" ""])
   :comment-with-content (parser/parse-line ": this is a comment")})

(defn test-event-accumulation!
  "Test SSE event accumulation with multiple data lines.

  Verifies that multiple consecutive data: lines are joined
  with newlines into a single event."
  []
  (let [opts {}
        state (parser/empty-state)
        step1 (parser/step state {} (parser/parse-line "data: line 1") opts)
        step2 (parser/step (:state step1) {} (parser/parse-line "data: line 2") opts)
        step3 (parser/step (:state step2) {} (parser/parse-line "data: line 3") opts)]
    {:data-lines (:data-lines (:state step3))
     :count (count (:data-lines (:state step3)))}))

(comment
  ;; Run parser tests

  ;; max-data-lines limit
  (def result-limit (test-max-data-lines-limit!))
  (:event result-limit)
  (:flush result-limit)

  ;; Basic parsing
  (def result-basic (test-basic-parsing!))
  (:kind result-basic)
  (:data-field result-basic)

  ;; Edge cases
  (def result-edge (test-edge-cases!))

  ;; Event accumulation
  (def result-accum (test-event-accumulation!))
  )
