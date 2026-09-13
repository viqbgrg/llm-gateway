package com.llmgateway.model;

/** Protocol-neutral completion outcomes; an unrecognized provider reason must never become STOP. */
public enum FinishReason {
    STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, UNKNOWN
}
