package com.llmgateway.model;

/** Structural limits for normalized IR; these do not estimate model context tokens or HTTP body bytes. */
public final class LlmInputLimits {
    public static final int MAX_MODEL_NAME_LENGTH = 255;
    public static final int MAX_IDENTIFIER_LENGTH = 256;
    public static final int MAX_TOOL_NAME_LENGTH = 64;
    public static final int MAX_MESSAGES = 1_024;
    public static final int MAX_TOOLS = 128;
    public static final int MAX_BLOCKS_PER_MESSAGE = 256;
    public static final int MAX_CONTENT_BLOCKS = 4_096;
    public static final int MAX_PAYLOAD_CHARACTERS = 8 * 1_024 * 1_024;
    public static final int MAX_JSON_CHARACTERS = 1_024 * 1_024;
    public static final int MAX_JSON_DEPTH = 64;
    public static final int MAX_JSON_NODES = 16_384;
    public static final int MAX_STOP_SEQUENCES = 4;
    public static final int MAX_STOP_SEQUENCE_LENGTH = 1_024;

    private LlmInputLimits() {}
}
