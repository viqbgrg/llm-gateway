# Protocols and Internal IR

The internal representation is protocol-neutral. `LlmRequest`, `LlmResponse`, `LlmStreamEvent`, `Message`, and `ContentBlock` support text, image, thinking, tool calls/results and future media blocks. Client adapters and provider adapters convert to and from this IR; raw JSON string replacement is not part of the design.

Supported protocol identifiers are `CHAT_COMPLETIONS`, `ANTHROPIC`, and `RESPONSES`. Binding-level `sourceProtocol`, `targetProtocol`, and `translationEnabled` fields keep translation configurable.
