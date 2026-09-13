package com.llmgateway.model;

/** Selection for the next generated turn; historical calls need not use currently exposed tools. */
public sealed interface ToolChoice permits ToolChoice.Mode, ToolChoice.Named {
    enum Mode implements ToolChoice {
        AUTO, NONE, REQUIRED
    }

    record Named(String name) implements ToolChoice {
        public Named {
            IrValidation.requiredText(name, "tool choice name", LlmInputLimits.MAX_TOOL_NAME_LENGTH);
        }
    }
}
