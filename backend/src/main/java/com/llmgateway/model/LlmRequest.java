package com.llmgateway.model;

import java.util.List;

public record LlmRequest(
        String model,
        List<Message> messages,
        List<ToolDefinition> tools,
        ToolChoice toolChoice,
        ReasoningConfig reasoning,
        GenerationConfig generation,
        ResponseFormat responseFormat,
        boolean stream) {
    public LlmRequest {
        IrValidation.requiredText(model, "model", LlmInputLimits.MAX_MODEL_NAME_LENGTH);
        messages = IrValidation.requiredList(messages, "messages", LlmInputLimits.MAX_MESSAGES);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        tools = IrValidation.requiredList(tools == null ? List.of() : tools, "tools", LlmInputLimits.MAX_TOOLS);
        if (toolChoice == null) {
            toolChoice = tools.isEmpty() ? ToolChoice.Mode.NONE : ToolChoice.Mode.AUTO;
        }
        LlmRequestValidation.validateToolChoice(tools, toolChoice);
        LlmRequestValidation.validateToolHistory(messages);
        if (reasoning != null && reasoning.budgetTokens() != null && generation != null
                && generation.maxTokens() != null && reasoning.budgetTokens() >= generation.maxTokens()) {
            throw new IllegalArgumentException("reasoning budget must be less than maxTokens");
        }
        LlmRequestValidation.validateSize(model, messages, tools, toolChoice, generation, responseFormat);
    }

    public LlmRequest(String model, List<Message> messages, List<ToolDefinition> tools,
                      ReasoningConfig reasoning, GenerationConfig generation, ResponseFormat responseFormat,
                      boolean stream) {
        this(model, messages, tools, null, reasoning, generation, responseFormat, stream);
    }
}
