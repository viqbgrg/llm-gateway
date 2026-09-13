# Protocols and Internal IR

The internal representation is protocol-neutral. `LlmRequest`, `LlmResponse`, `LlmStreamEvent`, `Message`, and `ContentBlock` describe requests, responses and their content. Client adapters and provider adapters convert to and from this IR; raw JSON string replacement is not part of the design.

Supported protocol identifiers are `CHAT_COMPLETIONS`, `ANTHROPIC`, and `RESPONSES`. Binding-level `sourceProtocol`, `targetProtocol`, and `translationEnabled` fields keep translation configurable.

## Implemented wire subset

Verified on 2026-09-13; see [acceptance evidence](verification/acceptance.md). These are repository-defined, tested subsets, **not full parity with every field or version of the public APIs**. The fixture manifest's `chat-completions-2024-10` and `responses-2025-03` labels identify fixture contracts, not negotiated upstream API versions.

| Capability | Chat client | Anthropic client | Responses client |
| --- | --- | --- | --- |
| Endpoint | `/v1/chat/completions` | `/v1/messages` | `/v1/responses` |
| Text; complete JSON response; SSE | Yes | Yes, through explicit translation | Yes, through explicit translation |
| System / developer instructions | Message roles | Top-level `system`; no developer role | `instructions` and explicit system/developer input messages |
| User images | URL or base64 data URL | URL or base64 source | `input_image` URL or base64 data URL |
| Function definitions, tool choice, calls/results | Yes | `tool_use` / `tool_result` | `function_call` / `function_call_output` |
| Structured output controls | Text, JSON object, JSON Schema | Not supported | `text.format`: text, JSON object, JSON Schema |
| Multiple generated choices | `n` absent or 1 | One answer | One answer |
| Stateful conversation storage / retrieval | Not supported | Not supported | `store` absent or false; explicit input history only |
| Reasoning/thinking, audio, video, documents, built-in tools | Not supported | Not supported | Not supported |
| Native inference upstream | **Chat Completions only** | Not implemented | Not implemented |

Anthropic/Responses client requests require a binding with the matching `sourceProtocol`, `targetProtocol=CHAT_COMPLETIONS` and `translationEnabled=true`. A provider with a catalog protocol of `ANTHROPIC` or `RESPONSES` cannot thereby execute native inference. Effective capabilities are the intersection of the declared model/binding capabilities and the implemented adapter chain; declarations cannot enable missing transport features.

### HTTP envelope and accepted fields

All inference endpoints require JSON and the inference token (`GATEWAY_API_KEY`). Anthropic also accepts `x-api-key` instead of bearer authentication, but rejects requests supplying both. Anthropic requires `anthropic-version: 2023-06-01`. The separately configured admin key cannot authenticate inference, and access keys are never used as provider credentials.

| Client | Accepted top-level request fields |
| --- | --- |
| Chat | `model`, `messages`, `tools`, `tool_choice`, `temperature`, `top_p`, `max_tokens`, `seed`, `stop`, `response_format`, `stream`, `n`, `stream_options` |
| Anthropic | `model`, `messages`, `system`, `max_tokens`, `temperature`, `top_p`, `stop_sequences`, `tools`, `tool_choice`, `stream` |
| Responses | `model`, `input`, `instructions`, `tools`, `tool_choice`, `temperature`, `top_p`, `max_output_tokens`, `text`, `stream`, `store` |

- Typed DTO decoding rejects unknown fields, duplicate JSON keys and scalar coercion; nested protocol objects have explicit field allowlists. Unknown or unrepresentable semantics produce a safe error, not silent truncation.
- Anthropic `max_tokens` is mandatory. Chat `stream_options` supports only `include_usage=true` together with `stream=true`.
- Examples of rejected Chat parameters: `logprobs`, `parallel_tool_calls`, `reasoning_effort`, `max_completion_tokens`, and `n > 1`.
- Responses requires explicit `input`; `previous_response_id`, item references, background operation, `store=true`, built-in tools and stateful retrieval are not implemented.
- Responses accepts materialized message/function history, including its own `output` items copied into a later `input`. Optional `id` is nonblank and at most 512 characters; optional `status` is `completed` or `incomplete`. Output-text `annotations` must be absent, null or empty; nonempty annotations and unknown fields are rejected. Adjacent assistant text/function items are combined while preserving their order and tool associations.
- Function definitions accept an object parameter schema. Generated output is not locally validated against that schema. The more general IR ranges below are further restricted by each adapter.

### Semantic conversion

The immutable `LlmRequest.model` stays the client's logical name, including aliases. The provider adapter receives the real model name separately; concurrent candidates do not mutate shared IR. The client response uses the requested logical name, not the provider's model name.

Images are accepted only in **user** messages. URL/base64, MIME declarations, text/image order and tool-history association are preserved through IR. Chat/Responses image `detail` must be absent or `auto`; `high` and `low` are rejected. IR support for assistant media or media-valued tool results does not imply wire support.

Tools are function-only. History may include parallel calls and their associated results, with case-sensitive IDs, names and complete JSON object arguments. Results are text-only; an Anthropic result with `is_error=true` is rejected because the current upstream chain cannot preserve that flag. Mixed Anthropic user/tool-result blocks are normalized to the corresponding ordered IR messages. Named/auto/required/none choices map to the destination's supported equivalent; unsupported tool fields are rejected.

| IR finish reason | Chat | Anthropic | Responses |
| --- | --- | --- | --- |
| `STOP` | `stop` | `end_turn` | `completed` |
| `LENGTH` | `length` | `max_tokens` | `incomplete`, reason `max_output_tokens` |
| `TOOL_CALLS` | `tool_calls` | `tool_use` | Function-call output items, `completed` |
| `CONTENT_FILTER` | `content_filter` | `refusal` | `incomplete`, reason `content_filter` |
| `UNKNOWN` | Rejected by this upstream adapter | Not fabricated as a normal finish | Not fabricated as a normal finish |

Usage is copied only when reported. Missing or partial counters remain unknown; no zero or total is invented. Anthropic exposes its input/output fields, while Chat/Responses can also expose a reported total. A cancelled upstream may still incur charges even when no usage was returned.

Anthropic SSE always includes `message_start.message.usage` and `message_delta.usage`, allowing SDKs to accumulate the final message. Unknown input/output counts are JSON `null`, including at message start before upstream usage is available; final reported counts are retained. The default Python `anthropic==1.5.0` client's public `messages.stream().get_final_message()` is verified for text/tools and full/partial/absent usage. Strict client-side validation that requires integer counts for unknown usage is outside that compatibility check.

### Transport, streaming and errors

The HTTP layer reads `ChatRequest`, `AnthropicRequest` or `ResponsesRequest` with `bodyToMono`. Generic `ClientProtocolAdapter<I, O>` implementations perform pure parsing/encoding; domain routing and execution are outside controllers. The transport uses explicit response DTOs and `ServerSentEvent<String>`, not untyped `Mono<Object>` or blocking request-body reads.

`ChatSseDecoder` handles incremental UTF-8, SSE boundaries, comments, role-only chunks, interleaved function arguments, usage and terminal markers. The three encoders emit their respective Chat chunks, Anthropic content/message events and Responses output/text/function events. Metadata or heartbeat arrival is not TTFT or a hedge win. Only one source is forwarded; once client SSE is committed, errors terminate that source and never splice in a fallback answer. Disconnects cancel active calls and pending work.

Defaults: 60-second logical deadline; 8 MiB request and upstream JSON response limits; 1 MiB per SSE frame; 30-second stream-idle/slow-consumer guard. Request bytes are bounded even without `Content-Length`; response/frame overflow, invalid UTF-8, incomplete tool JSON and premature EOF fail safely. IR argument/output buffers have additional bounds below; no streaming path relies on collecting an unbounded response. See [development configuration](development.md) and [execution policy](routing.md).

Before response commitment, failures use the entry protocol's JSON error envelope and HTTP status. Afterwards, a safe protocol-specific SSE error closes the stream. Input/compatibility errors do not dispatch or penalize a provider. Provider authentication errors are configuration failures, not a client 401. Request IDs are validated, error bodies never forward upstream text, and raw content/credentials must not be logged.

## Typed content blocks (W0.1)

`ContentBlock` is a sealed interface. Each immutable record fixes its `ContentBlockType`; callers cannot pair a type with an unrelated payload. `ContentBlock.text(value)` remains available as a convenience factory.

| Type | Record | Payload |
| --- | --- | --- |
| `TEXT` | `ContentBlock.Text` | Text, preserving whitespace and empty strings |
| `IMAGE` | `ContentBlock.Image` | `MediaSource`, with an `image/*` MIME type when declared |
| `THINKING` | `ContentBlock.Thinking` | Thinking text, preserving whitespace and empty strings |
| `TOOL_CALL` | `ToolCall` | Nonblank call ID and tool name, plus a complete JSON object of arguments |
| `TOOL_RESULT` | `ToolResult` | Nonblank call ID, ordered text/media blocks, and an error flag |
| `AUDIO` | `ContentBlock.Audio` | `MediaSource`, with an `audio/*` MIME type when declared |
| `VIDEO` | `ContentBlock.Video` | `MediaSource`, with a `video/*` MIME type when declared |
| `DOCUMENT` | `ContentBlock.Document` | `MediaSource`, supporting document MIME types such as `application/pdf` or `text/plain` |

Required payloads reject null. A tool result may be empty, but its list cannot be null or contain null entries, thinking, tool calls, or nested tool results. JSON returned as textual tool output is preserved in a text block. Tool result lists are defensively copied and unmodifiable. Tool argument JSON is copied both on construction and on access, including nested objects and arrays; mutable POJO/binary nodes, missing nodes and non-finite numbers are rejected. W0.2 validates result IDs against prior calls when constructing a request.

## Media sources

`MediaSource` is also sealed, with two immutable variants:

- `MediaSource.Url(URI url, String mimeType)` holds an absolute HTTP(S) URL with a host and without embedded user credentials. Path and query are preserved. MIME type may be null when unknown.
- `MediaSource.InlineData(String data, String mimeType)` holds nonempty standard base64, with or without padding, and a required MIME type. Data-URL prefixes, URL-safe base64 and whitespace are not accepted in `data`.

MIME types use concrete RFC 6838 `type/subtype` names without parameters and are normalized to lowercase. Image, audio and video blocks reject a declared MIME type from another category. Construction validates the declaration and base64 syntax; it does not fetch URLs, inspect media bytes or infer provider capabilities.

For example, the following sources represent distinct image inputs:

```java
ContentBlock remote = new ContentBlock.Image(
        new MediaSource.Url(URI.create("https://fixture.invalid/image.png"), null));
ContentBlock inline = new ContentBlock.Image(
        new MediaSource.InlineData(imageBase64, "image/png"));
```

These types reserve media representations in the IR. The implemented wire subset and HTTP bounds above are intentionally narrower; current implementation status is recorded in [the roadmap](roadmap.md).

## Request and message invariants (W0.2)

IR constructors enforce these rules without relying on HTTP validation. Errors identify the field or constraint and never include model input, tool arguments, schemas or identifiers. `LlmRequest.model` is a required nonblank logical name, preserved without trimming or rewriting. Requests require a nonempty message list; a message requires a role and nonempty content list. Lists are copied, immutable and cannot contain null entries. An empty text block remains valid and differs from an absent message.

| Message role | Allowed content |
| --- | --- |
| `SYSTEM`, `DEVELOPER`, `USER` | Text and media |
| `ASSISTANT` | Text, media, thinking and tool calls |
| `TOOL` | One or more tool results |

Client adapters must normalize protocol-specific tool-result containers into `TOOL` messages. This table describes IR invariants; a protocol adapter may support a narrower set of media or message roles.

Tool call IDs are case-sensitive and unique across the request. Each result must resolve exactly one earlier, still-pending call. Results from a parallel batch may arrive in any order, in one or several consecutive `TOOL` messages. All pending calls must have results before another non-tool message or the end of the request; orphan, duplicate, reused and unresolved IDs are rejected. Empty or error tool results still resolve their calls. Historical calls need not refer to the tools exposed for the next generated turn.

`tools = null` means no tools. Tool definitions require unique, case-sensitive nonblank names and object-valued JSON input schemas; descriptions may be null or empty. Schemas have the same defensive-copy and JSON-value restrictions as tool arguments. The IR checks JSON shape and bounds; JSON Schema dialect support and protocol-specific schema restrictions belong to the adapters.

`ToolChoice` has `Mode.AUTO`, `Mode.NONE`, `Mode.REQUIRED`, and `Named(name)` variants. An omitted choice becomes `NONE` with no tools or `AUTO` with tools. Every explicit choice other than `NONE` requires tools, and a named choice must match a declared name exactly. `NONE` may be used with a nonempty tool list and does not restrict historical calls.

## Generation and structured output (W0.2)

Absent `reasoning`, `generation` and `responseFormat` remain null. Optional scalar parameters remain unspecified rather than being replaced by provider defaults.

| Parameter | IR contract |
| --- | --- |
| `temperature` | Finite value in `[0, 2]`, or null |
| `topP` | Finite value in `[0, 1]`, or null; mutually exclusive with `temperature` in the initial IR |
| `maxTokens` | Positive integer, or null; output limit includes any reasoning budget |
| `seed` | Any Java integer, including zero and negative values, or null |
| `stopSequences` | Null becomes an empty list; up to four nonempty strings, each at most 1,024 characters; whitespace and order are preserved |
| `reasoning.enabled` / `budgetTokens` | Disabled reasoning requires a null budget; an enabled budget may be unspecified or positive; when both are supplied, `budgetTokens < maxTokens` |

Provider capability and numeric limits still require routing/adapter validation; these ranges do not assert that every provider accepts these parameters.

`ResponseFormat` has `Mode.TEXT`, `Mode.JSON_OBJECT`, and `JsonSchema(name, description, schema, strict)` variants. Schema metadata cannot accompany the other modes. A JSON Schema format requires a nonblank name and complete JSON object; description and strictness are optional. In particular, `strict = null` remains distinct from `false`. The schema is copied on construction and access. Boolean or string schema roots are outside this initial IR contract. Constructing a schema does not fetch references or validate generated output against it.

## Completed responses and usage (W0.2)

`LlmResponse` requires a nonblank response ID, logical model name, content list and `FinishReason`. Content is immutable, may be empty (for example after filtering), and follows the assistant role rules. Responses cannot contain tool results or duplicate tool call IDs. `FinishReason` is one of `STOP`, `LENGTH`, `TOOL_CALLS`, `CONTENT_FILTER`, or `UNKNOWN`; `TOOL_CALLS` requires at least one tool call. An unrecognized provider reason must remain `UNKNOWN` or be rejected by its adapter, never silently become `STOP`.

A missing `usage` is null. Each `Usage` counter (`inputTokens`, `outputTokens`, `totalTokens`) is independently nullable, so partial usage can preserve unknown counts. Known counts must be nonnegative; a supplied total must cover each known count, and when all three are supplied it must equal input plus output. Arithmetic checks handle the full nonnegative `long` range without overflow. An omitted total is not calculated, and a reported zero is retained as a known zero.

## IR input limits (W0.2)

`LlmInputLimits` defines the following limits. Character lengths use Java `String.length()` (UTF-16 code units).

| Scope | Limit |
| --- | --- |
| Logical model names | 255 characters |
| Tool call/result and response IDs | 256 characters |
| Tool names, named choices and response schema names | 64 characters |
| Messages / tool definitions per request | 1,024 / 128 |
| Content blocks per message or tool result | 256 |
| Total request content blocks, including blocks inside tool results | 4,096 |
| Total request payload | 8,388,608 characters, summed across string fields and compact serialized JSON objects |
| Each tool argument, tool input schema or response schema object | 1,048,576 serialized characters, 64 levels (root at level 1), 16,384 container/scalar nodes |

The aggregate budget includes text, thinking, inline base64, media URLs/MIME types, tool arguments/results, tool and response schemas/descriptions, model and tool identifiers, named choices, and stop sequences. JSON size includes property names and escaping. JSON traversal is bounded before serialization and copying, so excessively nested or cyclic programmatically constructed nodes are rejected safely. Completed responses allow at most 4,096 content blocks and use the same identifier/JSON limits.

These are normalized IR resource limits, not token estimates. The transport additionally bounds bytes before decoding request DTOs and while reading provider responses. Model context limits require a separate source of capability information.

## Stream event contract (W0.3)

`LlmStreamEvent` is a sealed interface with immutable record variants. One stream represents one assistant message. Every event carries the same nonblank `messageId`; an `Error` before the message starts may omit it if unknown. IDs and tool names use the W0.2 length limits and are preserved without trimming. `BlockEvent` exposes the shared `contentBlockIndex` for all content and tool events.

| Event type | Record | Additional payload |
| --- | --- | --- |
| `MESSAGE_START` | `MessageStart` | None |
| `CONTENT_BLOCK_START` | `ContentBlockStart` | Content index and `contentType`, restricted to `TEXT` or `THINKING` |
| `TEXT_DELTA` | `TextDelta` | Content index and exact text fragment |
| `THINKING_DELTA` | `ThinkingDelta` | Content index and exact thinking fragment |
| `CONTENT_BLOCK_END` | `ContentBlockEnd` | Content index of a text or thinking block |
| `TOOL_CALL_START` | `ToolCallStart` | Content index, complete `toolCallId` and `toolName` |
| `TOOL_CALL_DELTA` | `ToolCallDelta` | Content index, `toolCallId` and raw `argumentsDelta` string |
| `TOOL_CALL_END` | `ToolCallEnd` | Content index and `toolCallId` |
| `USAGE` | `UsageUpdate` | Nonnull `Usage`, whose individual counters may be unknown |
| `MESSAGE_END` | `MessageEnd` | Required `FinishReason` |
| `ERROR` | `Error` | Required `LlmStreamError`, with a fixed code and safe message |

`LlmStreamEventValidator` enforces sequence rules in addition to the constructors' field checks. Create one validator per ordered stream (per subscription when used with Reactor), call `accept(event)` before forwarding each event, and call `complete()` when the source completes normally. The validator is not thread-safe. A violation invalidates the instance and discards buffered arguments; its exception identifies only the constraint and never retains parser text or a cause. Cancellation and transport failure must not be converted into a successful completion.

- A normal stream begins with exactly one `MESSAGE_START` and ends with exactly one `MESSAGE_END`. `complete()` rejects an empty or truncated source. An `ERROR` can be the sole event, or terminate a started message even with open blocks; after start it must carry the active message ID. No event, including usage or another terminal event, may follow either terminal event.
- Text, thinking and tool blocks share consecutive indices starting at zero, assigned in normalized content order when each block starts. Indices cannot be skipped, reused or changed. These are IR indices, not raw provider tool-array indices. At most 4,096 blocks may start in one message.
- Text/thinking deltas require an open block of the matching type. `CONTENT_BLOCK_END` closes only text or thinking. Empty blocks and empty/whitespace deltas are valid and distinct from missing payloads.
- A tool start requires a complete ID and name; adapters must assemble fragmented metadata before emitting it. Call IDs are case-sensitive and unique for the whole message, including already closed calls. Multiple calls may have the same name. Every subsequent tool delta/end must match both the content index and call ID.
- Multiple blocks may remain open and their deltas may interleave. For example, `start A, start B, delta A, delta B, end B, delta A, end A` is valid for two tool calls. Consumers associate fragments by content index and tool ID, preserving each call's fragment order and the message's content-index order.
- `MESSAGE_END` requires all blocks to be closed. `TOOL_CALLS` additionally requires at least one completed tool call. All other W0.2 finish reasons remain distinct; a length limit does not make an unfinished tool call complete.
- Usage events may appear anywhere after message start and before the terminal event, including after all content ends. They are snapshots of reported counters, never increments to sum. Missing counters remain null; no usage event means usage is unknown. Adapters whose upstream reports usage after a finish marker must defer `MESSAGE_END` until usage has been processed.

Tool argument fragments are append-only strings, not JSON patches or individually parsed `JsonNode` values. A fragment can end inside a key, number, string or escape sequence, such as `{"city":"\u` followed by `676d\u5dde"}`. Empty fragments are preserved. Only `TOOL_CALL_END` checks the concatenated arguments: they must form exactly one complete JSON object, with no trailing tokens or duplicate object keys at any depth. A call without arguments must explicitly supply `{}`. Incomplete or invalid JSON cannot be closed as a successful call; an adapter must end the stream with a safe error when it cannot produce a valid completion.

Argument validation uses a token walk with the W0.2 depth/node limits and no second JSON tree. Each call is limited to 1,048,576 raw argument characters, including whitespace and escaping, across all its fragments. The total pending argument buffer is limited to 8,388,608 characters across open calls; closing a call releases its capacity. Text/thinking is not accumulated by the validator, and each individual delta is limited to 8,388,608 characters. These bounds use UTF-16 code units; the implemented SSE transport adds byte limits and bounded downstream buffering.

`LlmStreamError` permits only `upstream_error`, `timeout`, `rate_limited`, `invalid_response`, and `internal_error`, each with a predefined safe message. There is no arbitrary upstream error string, body, URL, header or exception cause in this payload. Protocol adapters encode these values into their own error envelopes; [routing and execution](routing.md) documents the implemented provider error classification. Event records contain generated content and must never be logged wholesale.

The event contract is transport-independent. HTTP/SSE codecs, cancellation and compatibility are implemented and tested separately; media streaming remains outside the supported wire subset.

## Contract verification

`ContentBlockTest`, `MediaSourceTest`, `LlmRequestValidationTest`, `GenerationConfigTest`, `JsonSchemaTest` and `LlmResponseValidationTest` cover payloads, role and tool associations, parameter combinations, missing usage, limits and defensive copying using synthetic data. `LlmStreamEventContractTest` covers event identity, legal order, interleaved calls, partial JSON, terminal events, safe errors and bounded argument buffering. Run the focused suite with:

```bash
./gradlew :backend:test --tests 'com.llmgateway.model.*'
```

`backend/src/test/resources/fixtures/manifest.json` links synthetic text/conversation requests, normalized IR or expected upstream JSON, upstream/client responses, raw SSE bytes, expected events and negative cases. `ProtocolAdaptersTest` compares three-protocol image/tool-history conversion and response encoding. `StreamingProtocolTest` varies byte fragmentation and checks all three encoders. `GatewayRuntimeIntegrationTest` exercises real HTTP authentication, capability refusals with zero calls, tools, structured output, body/frame limits, cancellation, safe errors and sanitized logs/metric tags.

`SdkFixtureGenerator` writes streams from the actual encoder for `scripts/verify-sdk.py`, which exercises the pinned official SDK through an in-memory HTTP transport. See [SDK verification](development.md#sdk-compatibility) for commands; no provider or SDK request reaches the network.

```bash
./gradlew test --tests 'com.llmgateway.protocol.*' --tests 'com.llmgateway.inference.*'
```
