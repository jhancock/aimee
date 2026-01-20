# Agentflow improvements (draft)

Working list of potential improvements to evaluate before changing code.

## Direction

Chat completions will remain a legacy/simpler path with a tightened support scope.
Responses API is the forward-looking path where richer features will be implemented.

## Chat completions

- [Done] Handle `stream_options.include_usage`: final chunk can have `choices: []` and only `usage`; include usage in `:complete` (when present).
- [Done] Skip non-`chat.completion.chunk` SSE payloads (Azure async filter edge case).
- [Keep] `finish_reason` values like `length` or `content_filter` are surfaced in `:complete` for consumers to handle.
- [Done] Set `n=1` explicitly and document that multi-choice (`n>1`) is not supported in this library.
- [Done] Parse `delta.refusal` and emit it in chunks and completion; add a completion flag to indicate refusal text. Assumption: refusal vs content is binary (no mixed text), though edge cases are possible.
- [Deferred] Support `logprobs` / `top_logprobs`: pass through request options, include logprobs in parsed chunk data and accumulate into `:complete` (choice 0 only while `n=1`).
- [Future] Tool calls are not supported today (no `tools`/`tool_choice` request fields). Revisit tool-call argument accumulation if tool support is added.
