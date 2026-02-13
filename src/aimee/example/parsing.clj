(ns aimee.example.parsing
  (:require [aimee.chat.parser :as parser]
            [aimee.chat.sse :as chat-sse]
            [aimee.sse :as sse]))

(defn test-non-chunk-payload!
  "Example 5: Local SSE stream with a non-chunk payload (should be skipped).

  Events without 'chat.completion.chunk' as object are skipped."
  []
  (let [sse-data
        (str
         "data: {\"object\":\"other.event\",\"data\":{}}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
         "data: [DONE]\n\n")
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        seen-events (atom [])
        final-info (atom nil)
        handlers (chat-sse/make-stream-handlers
                  {:emit! (fn [event _] (swap! seen-events conj event))
                   :complete! (fn [info] (reset! final-info info))
                   :error! (fn [ex] (swap! seen-events conj {:event :error :data ex}))
                   :stream nil
                   :parse-chunks? true
                   :on-parse-error :stop})]
    (sse/consume-sse!
     input
     {:accumulator parser/accumulate-content
      :initial-acc {:content ""}
      :on-event (:on-event handlers)
      :on-complete (:on-complete handlers)
      :on-error (:on-error handlers)})
    {:seen-events @seen-events
     :final-info @final-info}))

(defn test-usage-only-final-chunk!
  "Example 5b: Local SSE stream with usage-only final chunk.

  Tests capturing usage data from a final chunk with no choices array."
  []
  (let [sse-data
        (str
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}\n\n"
         "data: [DONE]\n\n")
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        usage-final (atom nil)
        usage-handlers (chat-sse/make-stream-handlers
                        {:emit! (fn [_event _] nil)
                         :complete! (fn [info] (reset! usage-final info))
                         :error! (fn [ex] (reset! usage-final {:error ex}))
                         :stream nil
                         :parse-chunks? true
                         :on-parse-error :stop})]
    (sse/consume-sse!
     input
     {:accumulator parser/accumulate-content
      :initial-acc {:content ""}
      :on-event (:on-event usage-handlers)
      :on-complete (:on-complete usage-handlers)
      :on-error (:on-error usage-handlers)})
    @usage-final))

(defn test-refusal-delta!
  "Example 5c: Local SSE stream with refusal delta.

  Tests handling of refusal deltas, which are normalized into
  content with :refusal? true flag."
  []
  (let [sse-data
        (str
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"refusal\":\"Sorry, I can't help with that.\"}}]}\n\n"
         "data: [DONE]\n\n")
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        refusal-final (atom nil)
        refusal-handlers (chat-sse/make-stream-handlers
                         {:emit! (fn [_event _] nil)
                          :complete! (fn [info] (reset! refusal-final info))
                          :error! (fn [ex] (reset! refusal-final {:error ex}))
                          :stream nil
                          :parse-chunks? true
                          :on-parse-error :stop})]
    (sse/consume-sse!
     input
     {:accumulator parser/accumulate-content
      :initial-acc {:content ""}
      :on-event (:on-event refusal-handlers)
      :on-complete (:on-complete refusal-handlers)
      :on-error (:on-error refusal-handlers)})
    @refusal-final))

(defn test-parse-error-stop!
  "Example 5d: Parse error policy (:stop) with malformed JSON.

  When :on-parse-error is :stop, malformed JSON causes the stream
  to error and stop. [DONE] still ends the stream."
  []
  (let [sse-data
        (str
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"BROKEN\"}}\n\n"
         "data: [DONE]\n\n")
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        bad-stop-events (atom [])
        bad-stop-complete (atom nil)
        handlers (chat-sse/make-stream-handlers
                  {:emit! (fn [event _] (swap! bad-stop-events conj event))
                   :complete! (fn [info] (reset! bad-stop-complete info))
                   :error! (fn [ex] (swap! bad-stop-events conj {:event :error :data ex}))
                   :stream nil
                   :parse-chunks? true
                   :on-parse-error :stop})]
    (sse/consume-sse!
     input
     {:accumulator parser/accumulate-content
      :initial-acc {:content ""}
      :on-event (:on-event handlers)
      :on-complete (:on-complete handlers)
      :on-error (:on-error handlers)})
    {:events @bad-stop-events
     :complete-info @bad-stop-complete}))

(defn test-parse-error-continue!
  "Example 5e: Parse error policy (:continue) with malformed JSON.

  When :on-parse-error is :continue, malformed chunks are skipped
  and the stream continues processing."
  []
  (let [sse-data
        (str
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"BROKEN\"}}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
         "data: [DONE]\n\n")
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        bad-continue-events (atom [])
        bad-continue-complete (atom nil)
        handlers (chat-sse/make-stream-handlers
                  {:emit! (fn [event _] (swap! bad-continue-events conj event))
                   :complete! (fn [info] (reset! bad-continue-complete info))
                   :error! (fn [ex] (swap! bad-continue-events conj {:event :error :data ex}))
                   :stream nil
                   :parse-chunks? true
                   :on-parse-error :continue})]
    (sse/consume-sse!
     input
     {:accumulator parser/accumulate-content
      :initial-acc {:content ""}
      :on-event (:on-event handlers)
      :on-complete (:on-complete handlers)
      :on-error (:on-error handlers)})
    {:events @bad-continue-events
     :complete-info @bad-continue-complete}))

(defn test-parse-chunks-option-local!
  "Example 5i: Local SSE parse-chunks option toggle.

  When :parse-chunks? is true, chunk event payloads include :parsed.
  When false, chunk events include raw SSE data only."
  [parse-chunks?]
  (let [sse-data
        (str
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
         "data: [DONE]\n\n")
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        seen-events (atom [])
        final-info (atom nil)
        handlers (chat-sse/make-stream-handlers
                  {:emit! (fn [event _] (swap! seen-events conj event))
                   :complete! (fn [info] (reset! final-info info))
                   :error! (fn [ex] (swap! seen-events conj {:event :error :data ex}))
                   :stream nil
                   :parse-chunks? parse-chunks?
                   :on-parse-error :stop})]
    (sse/consume-sse!
     input
     {:accumulator parser/accumulate-content
      :initial-acc {:content ""}
      :on-event (:on-event handlers)
      :on-complete (:on-complete handlers)
      :on-error (:on-error handlers)})
    (let [chunk (first (filter #(= :chunk (:event %)) @seen-events))]
      {:parse-chunks? parse-chunks?
       :chunk-count (count (filter #(= :chunk (:event %)) @seen-events))
       :has-parsed? (contains? (:data chunk) :parsed)
       :complete-info @final-info})))

(defn test-accumulate-option-local!
  "Example 5j: Local SSE accumulate option toggle.

  When :accumulate? is true, final :complete content contains full text.
  When false, final :complete content remains empty."
  [accumulate?]
  (let [sse-data
        (str
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
         "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
         "data: [DONE]\n\n")
        input (java.io.ByteArrayInputStream. (.getBytes sse-data "UTF-8"))
        seen-events (atom [])
        final-info (atom nil)
        handlers (chat-sse/make-stream-handlers
                  {:emit! (fn [event _] (swap! seen-events conj event))
                   :complete! (fn [info] (reset! final-info info))
                   :error! (fn [ex] (swap! seen-events conj {:event :error :data ex}))
                   :stream nil
                   :parse-chunks? true
                   :on-parse-error :stop})
        accumulator (when accumulate? parser/accumulate-content)]
    (sse/consume-sse!
     input
     {:accumulator accumulator
      :initial-acc {:content ""}
      :on-event (:on-event handlers)
      :on-complete (:on-complete handlers)
      :on-error (:on-error handlers)})
    {:accumulate? accumulate?
     :chunk-count (count (filter #(= :chunk (:event %)) @seen-events))
     :content (:content @final-info)
     :complete-info @final-info}))

(comment
  ;; Run parsing tests

  ;; Non-chunk payload
  (def result-5 (test-non-chunk-payload!))
  (count (:seen-events result-5))

  ;; Usage-only final chunk
  (def result-5b (test-usage-only-final-chunk!))
  (:usage result-5b)

  ;; Refusal delta
  (def result-5c (test-refusal-delta!))
  (:refusal? result-5c)

  ;; Parse error :stop policy
  (def result-5d (test-parse-error-stop!))
  (count (filter #(= :error (:event %)) (:events result-5d)))

  ;; Parse error :continue policy
  (def result-5e (test-parse-error-continue!))
  (count (filter #(= :chunk (:event %)) (:events result-5e)))
  (:content (:complete-info result-5e))

  ;; Parse-chunks option (local)
  (def result-5i-true (test-parse-chunks-option-local! true))
  (:has-parsed? result-5i-true)
  (def result-5i-false (test-parse-chunks-option-local! false))
  (:has-parsed? result-5i-false)

  ;; Accumulate option (local)
  (def result-5j-true (test-accumulate-option-local! true))
  (:content result-5j-true)
  (def result-5j-false (test-accumulate-option-local! false))
  (:content result-5j-false)
  )
