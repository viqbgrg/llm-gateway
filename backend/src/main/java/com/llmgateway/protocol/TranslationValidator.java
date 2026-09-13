package com.llmgateway.protocol;

import com.llmgateway.model.*;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The registry describes implemented inference chains, independently of catalog protocols. */
@Component
public class TranslationValidator {
    private static final Set<ModelCapability> CHAT = Set.of(ModelCapability.CHAT, ModelCapability.STREAMING,
            ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.STRUCTURED_OUTPUT);
    public boolean available(Protocol source, Protocol target, Protocol provider, boolean enabled) {
        return source != null && target == Protocol.CHAT_COMPLETIONS && target == provider
                && (source == target || enabled);
    }
    public Set<ModelCapability> capabilities(Protocol source, Protocol target) {
        if (target != Protocol.CHAT_COMPLETIONS) return Set.of();
        return source == Protocol.ANTHROPIC ? Set.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.VISION, ModelCapability.TOOLS) : CHAT;
    }
    public void validate(Protocol source, Protocol target, Protocol provider, boolean enabled) {
        if (!available(source, target, provider, enabled)) throw new IllegalArgumentException("Binding requires an implemented protocol chain, matching provider protocol, and explicit translation");
    }
}
