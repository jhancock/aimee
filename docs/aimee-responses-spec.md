# Agentflow Responses API streaming spec

Goal: parse SSE from OpenAI Responses API and emit protocol-faithful events into a caller-owned core.async channel.

This spec intentionally avoids abstracting or renaming Responses events. Event names should match the protocol verbatim.

## Scope

- Input: java.io.InputStream from OpenAI-compatible Responses streaming responses
- Output: events on caller-owned channel
- Transport: babashka/http-client (:as :stream)
- Concurrency: core.async 1.9.829-alpha2 +, Java 21+

## Channel contract

Caller creates and owns the channel. The library only puts events onto it.

Event shape:

{:event "<responses-event-name>"
 :data <payload map>}

Notes:
- Streaming emits multiple protocol events; there is no :chunk abstraction.
- Non-streaming returns a single Responses object (no SSE). If using a channel anyway, emit {:event "response.completed" :data <response>}.

## SSE framing

- SSE lines follow the standard "event:" + "data:" format.
- When an SSE event includes an "event" field, use that value verbatim for :event.
- When an SSE event has no "event" field, treat it as unknown and do not re-label it.
- A terminal "data: [DONE]" is an SSE sentinel and should be forwarded as a protocol-faithful terminal event (exact representation TBD once upstream spec is confirmed).

## Responses event catalog

Event names from the OpenAI Python SDK `ResponseStreamEvent` union
(`openai-python/src/openai/types/responses/response_stream_event.py`).
These are protocol event names and should be forwarded verbatim.

- response.audio.delta
- response.audio.done
- response.audio.transcript.delta
- response.audio.transcript.done
- response.code_interpreter_call_code.delta
- response.code_interpreter_call_code.done
- response.code_interpreter_call.completed
- response.code_interpreter_call.in_progress
- response.code_interpreter_call.interpreting
- response.completed
- response.content_part.added
- response.content_part.done
- response.created
- response.custom_tool_call_input.delta
- response.custom_tool_call_input.done
- response.failed
- response.file_search_call.completed
- response.file_search_call.in_progress
- response.file_search_call.searching
- response.function_call_arguments.delta
- response.function_call_arguments.done
- response.image_generation_call.completed
- response.image_generation_call.generating
- response.image_generation_call.in_progress
- response.image_generation_call.partial_image
- response.in_progress
- response.incomplete
- response.mcp_call_arguments.delta
- response.mcp_call_arguments.done
- response.mcp_call.completed
- response.mcp_call.failed
- response.mcp_call.in_progress
- response.mcp_list_tools.completed
- response.mcp_list_tools.failed
- response.mcp_list_tools.in_progress
- response.output_item.added
- response.output_item.done
- response.output_text.annotation.added
- response.output_text.delta
- response.output_text.done
- response.queued
- response.reasoning_summary_part.added
- response.reasoning_summary_part.done
- response.reasoning_summary_text.delta
- response.reasoning_summary_text.done
- response.reasoning_text.delta
- response.reasoning_text.done
- response.refusal.delta
- response.refusal.done
- response.web_search_call.completed
- response.web_search_call.in_progress
- response.web_search_call.searching

## Non-streaming behavior

- POST /responses with stream=false returns a single response object.
- No SSE events are emitted for non-streaming calls.
- If the channel API is used for consistency, emit only one event:
  {:event "response.completed" :data <response>}

## Open questions

- What is the exact terminal sentinel for Responses streaming? Is it [DONE], or is completion signaled only by response.completed?
- Do any Responses events omit the "event:" line? If so, how should those be handled?
- Are there any cases where the server sends both response.completed and [DONE]?
