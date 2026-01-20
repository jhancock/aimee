# Agentflow streaming spec

Goal: parse SSE from OpenAI chat completions and emit events into a caller-owned core.async channel.

## Scope

- Input: java.io.InputStream from OpenAI-compatible streaming responses
- Output: events on caller-owned channel (streaming emits chunks; non-streaming emits complete/error)
- Transport: babashka/http-client (:as :stream)
- Concurrency: core.async 1.9.829-alpha2 +, Java 21+

## Module layout

aimee/
  sse_parser.clj    # pure SSE line parsing
  sse.clj           # SSE IO loop + simplified SSE rendering
  http.clj          # HTTP request wrapper
  util.clj          # shared utilities
  simulator.clj     # REPL helpers
  stress.clj        # REPL stress tests
  chat/
    client.clj      # start-request!
    builder.clj     # request body/options
    parser.clj      # OpenAI JSON parsing
    executor.clj    # HTTP execution
    sse.clj         # chat-specific channel handlers

## Channel contract

Caller creates and owns the channel. The library only puts events onto it.

Event shapes:

{:event :chunk
 :data {:id "..." :type "..." :data "{...raw OpenAI JSON...}"
         ;; present when :parse-chunks? true
         :parsed {:content "..." :finish-reason nil
                  :role "assistant"
                  :tool-calls [...]
                  :function-call {...}
                  :done? false}}}

{:event :complete
 :data {:content "..."
        :finish-reason "stop"
        :role "assistant"
        :tool-calls [...]
        :function-call {...}
        :usage {...}
        :done-event {:id nil :type nil :data "[DONE]"}
        ;; :reason optional for non-normal completion (:eof, :stopped, :timeout)
        }}

{:event :error
 :data <exception>}

Notes:
- Streaming produces multiple :chunk events then a :complete.
- Non-streaming produces a single :complete (or :error).
- :usage is included in :complete when present in the API response (e.g. non-streaming usage or streaming with usage enabled).
- Refusal text is normalized into :content when content is blank; :refusal / :refusal? are only present when the API explicitly marks a refusal.

## Chat API entrypoint

aimee.chat.client/start-request!

Required:
- :channel, :model, :messages, :url, :api-key

Options:
- :stream? (default false)
- :headers (additional headers)
- :http-timeout-ms (HTTP request timeout in milliseconds)
- :include-usage? (default false, request usage stats on :complete when streaming)
- :parse-chunks? (default true, include :parsed in :chunk events)
- :accumulate? (default true, build :content for :complete events)
- :on-parse-error (default :stop, :continue to log and skip bad chunks)
- :overflow-max (default 10000, counts events not bytes)
- :overflow-mode (:queue default, :block for immediate backpressure)
- :channel-idle-timeout-ms (abort if no progress events can be emitted)
Note: chat completions are sent with `n=1` via `:choices-n 1` (multi-choice is not supported).

Returns:
- {:stop! fn}

## Backpressure and overflow

Default is a lazy overflow queue:
- Overflow queue is created only on first backpressure.
- Queue is bounded by :overflow-max and preserves ordering.
- When full, producer blocks until consumer drains.

Overflow mode :block:
- No overflow queue; producer blocks on the channel immediately.

## Idle-timeout

Optional :channel-idle-timeout-ms aborts the request when no events can be emitted for the interval.
It emits :complete with :reason :timeout and closes the stream.

## SSE parsing behavior

- Handles data:, id:, event:, retry: lines
- Blank line ends an event
- Multiple data: lines are joined with \n
- Non-`chat.completion.chunk` payloads are ignored.
Lenient behavior:
- EOF flush emits any buffered data
- Missing [DONE] yields :reason :eof

## Usage example

(require '[aimee.chat.client :as chat]
         '[clojure.core.async :as async])

(def ch (async/chan 64))
(def result (chat/start-request!
             {:url "https://api.openai.com/v1/chat/completions"
              :api-key "sk-..."
              :channel ch
              :model "gpt-4o-mini"
              :messages [{:role "user" :content "Hello!"}]
              :stream? true}))

(async/go-loop []
  (when-let [event (async/<! ch)]
    (case (:event event)
      :complete (println "Done:" (:data event))
      :error (println "Error:" (:data event))
      :chunk (println "Chunk:" (:data event))
      (recur))))

((:stop! result))

## Stress testing

aimee.stress/run-overflow-test!
- Local SSE stream + slow consumer (no network).

aimee.stress/run-idle-timeout-test!
- Exercises idle-timeout (requires OPENAI_API_KEY via parker.config).

## Idle-timeout example

(require '[aimee.chat.client :as chat]
         '[clojure.core.async :as async]
         '[parker.config :as config])

(def ch (async/chan 1))
(def result (chat/start-request!
             {:url (config/openai-url)
              :api-key (config/openai-key)
              :channel ch
              :model "gpt-4o-mini"
              :stream? true
              :channel-idle-timeout-ms 1500
              :messages [{:role "user" :content "Stream for a while."}]}))

(Thread/sleep 3000)
(def event (async/<!! ch))
(:data event)

## Next steps

- Add responses/ subdirectory for Responses API
- Provide a short server integration example
