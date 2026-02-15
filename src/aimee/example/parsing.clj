(ns aimee.example.parsing
  (:require [aimee.chat.parser :as parser]
            [aimee.chat.sse :as chat-sse]
            [aimee.sse :as sse]))

(comment
  ;; ---------------------------------------------------------------------------
  ;; Parsing module walkthrough - explicit REPL steps
  ;; ---------------------------------------------------------------------------
  ;;
  ;; Chat-specific SSE parsing: non-chunk events, usage, refusal, parse errors.
  ;; All examples are local (no network) for fast REPL feedback.
  ;; For low-level SSE line parsing, see aimee.example.parser.
  ;;
  ;; ---------------------------------------------------------------------------

  ;; Example 1: Local SSE stream with a non-chunk payload (should be skipped)
  ;; Events without 'chat.completion.chunk' as object are skipped.
  (def sse-data-1
    (str
     "data: {\"object\":\"other.event\",\"data\":{}}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-1 (java.io.ByteArrayInputStream. (.getBytes sse-data-1 "UTF-8")))
  (def seen-events-1 (atom []))
  (def final-info-1 (atom nil))
  (def handlers-1
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! seen-events-1 conj event))
      :complete! (fn [info] (reset! final-info-1 info))
      :error! (fn [ex] (swap! seen-events-1 conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-1
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-1)
    :on-complete (:on-complete handlers-1)
    :on-error (:on-error handlers-1)})
  @seen-events-1
  @final-info-1
  (count @seen-events-1)

  ;; Example 2: Local SSE stream with usage-only final chunk
  ;; Tests capturing usage data from a final chunk with no choices array.
  (def sse-data-2
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}\n\n"
     "data: [DONE]\n\n"))
  (def input-2 (java.io.ByteArrayInputStream. (.getBytes sse-data-2 "UTF-8")))
  (def usage-final-2 (atom nil))
  (def usage-handlers-2
    (chat-sse/make-stream-handlers
     {:emit! (fn [_event _] nil)
      :complete! (fn [info] (reset! usage-final-2 info))
      :error! (fn [ex] (reset! usage-final-2 {:error ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-2
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event usage-handlers-2)
    :on-complete (:on-complete usage-handlers-2)
    :on-error (:on-error usage-handlers-2)})
  @usage-final-2
  (:usage @usage-final-2)

  ;; Example 3: Local SSE stream with refusal delta
  ;; Tests handling of refusal deltas, which are normalized into content with :refusal? true flag.
  (def sse-data-3
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"refusal\":\"Sorry, I can't help with that.\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-3 (java.io.ByteArrayInputStream. (.getBytes sse-data-3 "UTF-8")))
  (def refusal-final-3 (atom nil))
  (def refusal-handlers-3
    (chat-sse/make-stream-handlers
     {:emit! (fn [_event _] nil)
      :complete! (fn [info] (reset! refusal-final-3 info))
      :error! (fn [ex] (reset! refusal-final-3 {:error ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-3
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event refusal-handlers-3)
    :on-complete (:on-complete refusal-handlers-3)
    :on-error (:on-error refusal-handlers-3)})
  @refusal-final-3
  (:refusal? @refusal-final-3)

  ;; Example 4: Parse error policy (:stop) with malformed JSON
  ;; When :on-parse-error is :stop, malformed JSON causes the stream to error and stop.
  (def sse-data-4
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"BROKEN\"}}\n\n"
     "data: [DONE]\n\n"))
  (def input-4 (java.io.ByteArrayInputStream. (.getBytes sse-data-4 "UTF-8")))
  (def bad-stop-events-4 (atom []))
  (def bad-stop-complete-4 (atom nil))
  (def handlers-4
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! bad-stop-events-4 conj event))
      :complete! (fn [info] (reset! bad-stop-complete-4 info))
      :error! (fn [ex] (swap! bad-stop-events-4 conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-4
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-4)
    :on-complete (:on-complete handlers-4)
    :on-error (:on-error handlers-4)})
  @bad-stop-events-4
  @bad-stop-complete-4
  (count (filter #(= :error (:event %)) @bad-stop-events-4))

  ;; Example 5: Parse error policy (:continue) with malformed JSON
  ;; When :on-parse-error is :continue, malformed chunks are skipped and the stream continues.
  (def sse-data-5
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"BROKEN\"}}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-5 (java.io.ByteArrayInputStream. (.getBytes sse-data-5 "UTF-8")))
  (def bad-continue-events-5 (atom []))
  (def bad-continue-complete-5 (atom nil))
  (def handlers-5
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! bad-continue-events-5 conj event))
      :complete! (fn [info] (reset! bad-continue-complete-5 info))
      :error! (fn [ex] (swap! bad-continue-events-5 conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :continue}))
  (sse/consume-sse!
   input-5
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-5)
    :on-complete (:on-complete handlers-5)
    :on-error (:on-error handlers-5)})
  @bad-continue-events-5
  @bad-continue-complete-5
  (count (filter #(= :chunk (:event %)) @bad-continue-events-5))
  (:content @bad-continue-complete-5)

  ;; Example 6: Local SSE parse-chunks option toggle (true)
  ;; When :parse-chunks? is true, chunk event payloads include :parsed.
  (def sse-data-6
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-6 (java.io.ByteArrayInputStream. (.getBytes sse-data-6 "UTF-8")))
  (def seen-events-6 (atom []))
  (def final-info-6 (atom nil))
  (def handlers-6
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! seen-events-6 conj event))
      :complete! (fn [info] (reset! final-info-6 info))
      :error! (fn [ex] (swap! seen-events-6 conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-6
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-6)
    :on-complete (:on-complete handlers-6)
    :on-error (:on-error handlers-6)})
  (def chunk-6 (first (filter #(= :chunk (:event %)) @seen-events-6)))
  (contains? (:data chunk-6) :parsed)

  ;; Example 7: Local SSE parse-chunks option toggle (false)
  ;; When :parse-chunks? is false, chunk events include raw SSE data only.
  (def sse-data-7
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-7 (java.io.ByteArrayInputStream. (.getBytes sse-data-7 "UTF-8")))
  (def seen-events-7 (atom []))
  (def final-info-7 (atom nil))
  (def handlers-7
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! seen-events-7 conj event))
      :complete! (fn [info] (reset! final-info-7 info))
      :error! (fn [ex] (swap! seen-events-7 conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? false
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-7
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-7)
    :on-complete (:on-complete handlers-7)
    :on-error (:on-error handlers-7)})
  (def chunk-7 (first (filter #(= :chunk (:event %)) @seen-events-7)))
  (contains? (:data chunk-7) :parsed)

  ;; Example 8: Local SSE accumulate option toggle (true)
  ;; When :accumulate? is true, final :complete content contains full text.
  (def sse-data-8
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-8 (java.io.ByteArrayInputStream. (.getBytes sse-data-8 "UTF-8")))
  (def seen-events-8 (atom []))
  (def final-info-8 (atom nil))
  (def handlers-8
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! seen-events-8 conj event))
      :complete! (fn [info] (reset! final-info-8 info))
      :error! (fn [ex] (swap! seen-events-8 conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-8
   {:accumulator parser/accumulate-content
    :initial-acc {:content ""}
    :on-event (:on-event handlers-8)
    :on-complete (:on-complete handlers-8)
    :on-error (:on-error handlers-8)})
  (:content @final-info-8)

  ;; Example 9: Local SSE accumulate option toggle (false)
  ;; When :accumulate? is false, final :complete content remains empty.
  (def sse-data-9
    (str
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
     "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
     "data: [DONE]\n\n"))
  (def input-9 (java.io.ByteArrayInputStream. (.getBytes sse-data-9 "UTF-8")))
  (def seen-events-9 (atom []))
  (def final-info-9 (atom nil))
  (def handlers-9
    (chat-sse/make-stream-handlers
     {:emit! (fn [event _] (swap! seen-events-9 conj event))
      :complete! (fn [info] (reset! final-info-9 info))
      :error! (fn [ex] (swap! seen-events-9 conj {:event :error :data ex}))
      :stream nil
      :parse-chunks? true
      :on-parse-error :stop}))
  (sse/consume-sse!
   input-9
   {:accumulator nil
    :initial-acc {:content ""}
    :on-event (:on-event handlers-9)
    :on-complete (:on-complete handlers-9)
    :on-error (:on-error handlers-9)})
  (:content @final-info-9)
  )
