# Aimee Code Review

## Architecture Overview

Aimee is a Clojure library for consuming OpenAI-compatible chat completion APIs with SSE streaming. The architecture is well-layered:

| Layer | Namespace | Responsibility |
|-------|-----------|----------------|
| Entry point | `chat.client` | Orchestrates request lifecycle |
| Execution | `chat.executor` | HTTP dispatch (streaming/non-streaming) |
| SSE transport | `sse` / `sse_parser` | Generic SSE frame parsing & consumption |
| Chat parsing | `chat.parser` | OpenAI payload parsing, content accumulation |
| Emission | `chat.emitter` | Channel writes with backpressure strategies |
| Validation | `chat.options` | Spec-based option validation |
| Timeout | `chat.timeout` | Idle consumer detection |
| Scheduling | `scheduler` | Shared daemon thread for periodic tasks |
| HTTP | `http` | Thin babashka.http-client wrapper |
| Integration | `chat.ring` | Ring `StreamableResponseBody` for SSE to browsers |

The data flow is clean: `client -> executor -> sse (consume-sse!) -> sse_parser (step) -> chat.parser (accumulate) -> chat.emitter (emit to channel)`.

---

## Bugs

### 1. Scheduler executor leak on race (`scheduler.clj:18-24`)

`ensure-executor!` has a TOCTOU race. Two threads can both see `executor` as nil, both create new executors, and the `swap!` overwrites unconditionally -- the losing executor is never shut down, leaking a thread.

```clojure
;; Thread A reads executor as nil
;; Thread B reads executor as nil
;; Both create new executors
(let [created (Executors/newSingleThreadScheduledExecutor ...)]
  (swap! scheduler-state assoc :executor created) ;; last writer wins, other executor leaks
  created)
```

Fix: use `compare-and-set!` semantics or a swap function that checks:

```clojure
(defn- ensure-executor! []
  (let [created (Executors/newSingleThreadScheduledExecutor (make-thread-factory))]
    (swap! scheduler-state
           (fn [{:keys [executor] :as state}]
             (if (and executor (not (.isShutdown executor)))
               state
               (assoc state :executor created))))
    (:executor @scheduler-state)))
```

### 2. Shutdown nils executor even when timers exist (`scheduler.clj:43-46`)

The shutdown callback unconditionally sets `:executor nil`:

```clojure
(fn [{:keys [executor timers] :as inner-state}]
  (when (and executor (empty? timers))
    (.shutdown executor))
  (assoc inner-state :executor nil :shutdown-task nil))
```

When timers exist (added between schedule and execution), the executor is *not* shut down (correct) but is *still set to nil* (bug). The running executor becomes orphaned and future `ensure-executor!` calls create a new one while the old one's scheduled tasks run in limbo.

### 3. `reset-for-testing!` leaks executor (`scheduler.clj:84-90`)

Resets the atom without calling `.shutdown` on the executor. If the executor is alive, it becomes orphaned.

### 4. Lenient parse silently produces empty chunk (`chat/parser.clj:61-64`)

When `parse-sse-event` catches a JSON exception, it returns nil from the parse function. `parse-sse-payload` then receives nil as `payload`, and since `(get nil :object)` returns nil, it falls through to the else branch which creates a chunk with `:content ""` and `:done? false`. A malformed JSON event becomes an invisible empty chunk rather than signaling any problem.

### 5. Duplicate `await-terminal-event!!` (`example/control.clj:7-27` and `example/backpressure.clj:8-28`)

Identical function copy-pasted across two namespaces.

---

## Concerns

### 6. `.put` on bounded queue blocks forever (`chat/emitter.clj:20, 53, 66`)

When the overflow queue is full and the drain thread is dead or stuck, `.put` blocks the SSE reader thread indefinitely with no timeout. This could hang the entire streaming pipeline.

### 7. No graceful library shutdown API

The scheduler auto-shuts down after 60s idle, but there's no explicit shutdown function. In environments where the JVM keeps running (servers), a cancelled request's timeout timer could keep the scheduler alive indefinitely.

### 8. `chat.ring/consume-channel!` doesn't handle InterruptedException

It uses `async/<!!` which is blocking-interruptible. If the HTTP thread is interrupted, the exception propagates without writing `[DONE]` to the output stream.

### 9. Tight coupling to OpenAI wire format

`chat.parser` hardcodes `[:choices 0 ...]`, so multi-choice responses silently lose data. The spec constrains `choices-n` to `#{1}`, but the field exists, creating a misleading API.

### 10. No input sanitization on `url`

The URL is spec-validated as a non-blank string, but not as a valid URL. A malformed URL will only fail at HTTP request time with a less helpful error.

---

## Clojure Idiom Assessment

**Well-done:**
- Channel ownership: caller creates/owns channels, library only writes -- excellent pattern
- Consistent event shape `{:event <keyword> :data <payload>}`
- `comment` blocks for REPL-driven examples are thorough and well-documented
- `cond->` threading for conditional `assoc` -- idiomatic
- Factory functions (`make-channel-callbacks`, `make-stream-handlers`) with clear docstrings
- `parse-sse-event` vs `parse-sse-event!` -- bang convention for throwing variant is correct

**Could improve:**

### 11. Duplicate utility functions

`non-blank-string?` is defined in both `chat.options` and `chat.executor`. Should live in `util.clj`.

### 12. Deep nesting in `consume-sse!` (`sse.clj:100-197`)

The main loop is nested 6+ levels deep. The body could be extracted into helper functions for readability. The `handle-parsed-line` and `handle-complete` helpers are a good start, but the main loop itself is still a monolith.

### 13. Mixed concerns in namespace naming

`aimee.sse` (generic SSE) vs `aimee.chat.sse` (chat-specific SSE handlers) is confusing. Consider `aimee.sse.consumer` / `aimee.sse.parser` / `aimee.chat.stream` or similar.

### 14. `spec` for validation but not for public API

Spec is used internally for option validation but not exposed as `s/fdef` for instrumentation or documentation. The `validate-opts!` approach works but misses the tooling benefits of full spec instrumentation.

### 15. `atom` overuse where `volatile!` would suffice

Atoms like `stop?`, `terminated?`, `block-warning-emitted?` are per-request and single-threaded in practice (or have single-writer semantics). `volatile!` would be more appropriate and faster.

---

## Summary

The library is well-architected overall -- clean separation of concerns, good docstrings, and solid core.async patterns. The most impactful issues are the **scheduler race conditions** (bugs 1-3), which could leak threads under concurrent load, and the **blocking `.put` on overflow queue** (concern 6), which could hang the pipeline. The other issues are more about robustness and maintenance than correctness.
