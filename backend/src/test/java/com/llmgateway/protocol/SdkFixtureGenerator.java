package com.llmgateway.protocol;

import com.llmgateway.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

import static com.llmgateway.protocol.ProtocolJson.*;

/** Generates fixture traffic from the real encoder; no provider or credential is involved. */
public final class SdkFixtureGenerator {
    private SdkFixtureGenerator() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        var fixtures = MAPPER.createArrayNode();
        Usage[] samples = {null, new Usage(4L, 2L, 6L), new Usage(null, 2L, null), new Usage(4L, null, null)};
        for (int index = 0; index < samples.length; index++) {
            for (boolean tool : List.of(false, true)) {
                List<LlmStreamEvent> events = new ArrayList<>(List.of(new LlmStreamEvent.MessageStart("fixture-message"),
                        new LlmStreamEvent.ContentBlockStart("fixture-message", 0, ContentBlockType.TEXT),
                        new LlmStreamEvent.TextDelta("fixture-message", 0, "synthetic answer"),
                        new LlmStreamEvent.ContentBlockEnd("fixture-message", 0)));
                if (tool) events.addAll(List.of(new LlmStreamEvent.ToolCallStart("fixture-message", 1, "fixture-call", "lookup"),
                        new LlmStreamEvent.ToolCallDelta("fixture-message", 1, "fixture-call", "{\"value\":1}"),
                        new LlmStreamEvent.ToolCallEnd("fixture-message", 1, "fixture-call")));
                Usage usage = samples[index];
                if (usage != null) events.add(new LlmStreamEvent.UsageUpdate("fixture-message", usage));
                events.add(new LlmStreamEvent.MessageEnd("fixture-message", tool ? FinishReason.TOOL_CALLS : FinishReason.STOP));
                String wire = ClientSseEncoder.anthropic(Flux.fromIterable(events), "fixture-model")
                        .map(event -> "event: " + event.event() + "\ndata: " + event.data() + "\n\n")
                        .collect(Collectors.joining()).block();
                fixtures.add(object().put("name", "usage-" + index + (tool ? "-tool" : "-text")).put("stream", wire)
                        .put("input_tokens", usage == null ? null : usage.inputTokens())
                        .put("output_tokens", usage == null ? null : usage.outputTokens()).put("tool", tool));
            }
        }
        Files.writeString(directory.resolve("anthropic.json"), fixtures.toPrettyString());
    }
}
