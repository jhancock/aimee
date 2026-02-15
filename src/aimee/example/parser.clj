(ns aimee.example.parser
  (:require [aimee.sse-parser :as parser]))

(comment
  ;; ---------------------------------------------------------------------------
  ;; SSE Parser module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------
  ;;
  ;; Low-level SSE line parsing (see aimee.example.parsing for chat-specific).
  ;; All examples are local (no network) for fast REPL feedback.
  ;;
  ;; ---------------------------------------------------------------------------

  ;; Example 1: Basic SSE line parsing behavior
  (def blank-line (parser/parse-line ""))
  (def comment-line (parser/parse-line ":comment"))
  (def data-field (parser/parse-line "data: hello world"))
  (def id-field (parser/parse-line "id: msg-123"))
  (def event-field (parser/parse-line "event: message"))
  (def retry-field (parser/parse-line "retry: 3000"))
  (def unknown-field (parser/parse-line "unknown: value"))
  (def no-colon (parser/parse-line "no colon here"))
  (def eof (parser/parse-line nil))
  blank-line
  comment-line
  data-field
  id-field
  event-field
  retry-field
  unknown-field
  no-colon
  eof

  ;; Example 2: Edge cases in SSE parsing
  (def value-with-space (parser/parse-line "data:  leading space"))
  (def value-no-space (parser/parse-line "data:no leading space"))
  (def multi-colons (parser/parse-line "data: key:value"))
  (def empty-val (parser/parse-line "data: "))
  (def multi-blanks (map parser/parse-line ["" "" ""]))
  (def comment-content (parser/parse-line ": this is a comment"))
  value-with-space
  value-no-space
  multi-colons
  empty-val
  multi-blanks
  comment-content

  ;; Example 3: SSE event accumulation with multiple data lines
  ;; Multiple consecutive data: lines are joined with newlines into a single event.
  (def accum-opts {})
  (def accum-state (parser/empty-state))
  (def accum-step1 (parser/step accum-state {} (parser/parse-line "data: line 1") accum-opts))
  (def accum-step2 (parser/step (:state accum-step1) {} (parser/parse-line "data: line 2") accum-opts))
  (def accum-step3 (parser/step (:state accum-step2) {} (parser/parse-line "data: line 3") accum-opts))
  (:data-lines (:state accum-step3))
  (count (:data-lines (:state accum-step3)))

  ;; Example 4: max-data-lines limit forcing a flush
  ;; When the limit is exceeded, the parser emits the accumulated event and resets.
  (def limit-opts {:max-data-lines 3})
  (def limit-state (parser/empty-state))
  (def limit-step1 (parser/step limit-state {} (parser/parse-line "data: line1") limit-opts))
  (def limit-step2 (parser/step (:state limit-step1) {} (parser/parse-line "data: line2") limit-opts))
  (def limit-step3 (parser/step (:state limit-step2) {} (parser/parse-line "data: line3") limit-opts))
  (def limit-step4 (parser/step (:state limit-step3) {} (parser/parse-line "data: line4") limit-opts))
  (:event limit-step4)
  (:flush limit-step4)
  )
