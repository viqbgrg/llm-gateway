package com.llmgateway.provider.chat;

import com.llmgateway.inference.GatewayException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

public final class SseFramer {
    private final int limit;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private int eventBytes;
    private boolean cr;
    private boolean first = true;
    private final Runnable onEvent;
    public SseFramer(int limit) { this(limit, () -> {}); }
    private SseFramer(int limit, Runnable onEvent) { this.limit = limit; this.onEvent = onEvent; }
    public static Flux<String> decode(Flux<DataBuffer> input, int limit) {
        return decode(input, limit, () -> {});
    }
    public static Flux<String> decode(Flux<DataBuffer> input, int limit, Runnable onEvent) {
        return Flux.defer(() -> {
            SseFramer parser = new SseFramer(limit, onEvent);
            return input.concatMapIterable(buffer -> {
                try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return parser.accept(bytes); }
                finally { DataBufferUtils.release(buffer); }
            }, 1).concatWith(Flux.defer(() -> { parser.complete(); return Flux.empty(); }))
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        });
    }
    public List<String> accept(byte[] bytes) {
        var events = new ArrayList<String>();
        for (byte value : bytes) {
            int b = value & 0xff;
            if (cr) { cr = false; if (b == '\n') continue; }
            if (++eventBytes > limit) throw GatewayException.response();
            if (b == '\r' || b == '\n') { line(events); cr = b == '\r'; }
            else line.write(b);
        }
        return events;
    }
    private void line(List<String> events) {
        String value;
        try { value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString(); }
        catch (Exception ignored) { throw GatewayException.response(); }
        line.reset();
        if (first) { first = false; if (value.startsWith("\uFEFF")) value = value.substring(1); }
        if (value.isEmpty()) {
            onEvent.run();
            if (!data.isEmpty()) { data.setLength(data.length() - 1); events.add(data.toString()); data.setLength(0); }
            eventBytes = 0;
        } else if (value.equals("data") || value.startsWith("data:")) {
            String fragment = value.length() > 4 ? value.substring(5) : "";
            if (fragment.startsWith(" ")) fragment = fragment.substring(1);
            data.append(fragment).append('\n');
        }
    }
    public void complete() { if (line.size() != 0 || !data.isEmpty()) throw GatewayException.response(); }
}
