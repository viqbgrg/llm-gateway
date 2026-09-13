package com.llmgateway.routing;

import com.llmgateway.model.*;
import java.util.EnumSet;
import java.util.Set;

public final class RequestCapabilityExtractor {
    private RequestCapabilityExtractor() {}
    public static Set<ModelCapability> extract(LlmRequest request) {
        var required = EnumSet.of(ModelCapability.CHAT);
        if (request.stream()) required.add(ModelCapability.STREAMING);
        if (!request.tools().isEmpty()) required.add(ModelCapability.TOOLS);
        if (request.reasoning() != null && request.reasoning().enabled()) required.add(ModelCapability.REASONING);
        if (request.responseFormat() != null && request.responseFormat() != ResponseFormat.Mode.TEXT) required.add(ModelCapability.STRUCTURED_OUTPUT);
        request.messages().forEach(m -> m.content().forEach(b -> add(required, b)));
        return Set.copyOf(required);
    }
    private static void add(Set<ModelCapability> required, ContentBlock block) {
        switch (block) {
            case ContentBlock.Text ignored -> { }
            case ContentBlock.Image ignored -> required.add(ModelCapability.VISION);
            case ContentBlock.Thinking ignored -> required.add(ModelCapability.REASONING);
            case ContentBlock.Audio ignored -> required.add(ModelCapability.AUDIO);
            case ContentBlock.Video ignored -> required.add(ModelCapability.VIDEO);
            case ContentBlock.Document ignored -> required.add(ModelCapability.LONG_CONTEXT);
            case ToolCall ignored -> required.add(ModelCapability.TOOLS);
            case ToolResult result -> { required.add(ModelCapability.TOOLS); result.content().forEach(b -> add(required, b)); }
        }
    }
}
