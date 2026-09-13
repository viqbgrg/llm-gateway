package com.llmgateway.infrastructure;

import com.llmgateway.health.AttemptOutcome;
import com.llmgateway.model.*;
import com.llmgateway.resilience.HedgedRequestExecutor.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GatewayMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayMetrics.class);
    private final MeterRegistry registry;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> circuitStates = new java.util.concurrent.ConcurrentHashMap<>();
    public GatewayMetrics(MeterRegistry registry) { this.registry = registry; }
    public void request(Protocol protocol, boolean stream, String outcome, long nanos, Usage usage) {
        String[] tags = {"protocol", protocol.name(), "stream", Boolean.toString(stream), "outcome", outcome};
        registry.counter("llm.gateway.requests", tags).increment();
        Timer.builder("llm.gateway.request.duration").tags(tags).publishPercentileHistogram().register(registry).record(nanos, TimeUnit.NANOSECONDS);
        tokens("logical", usage);
    }
    public void attempt(String requestId, String attemptId, String virtualModelId, String bindingId, String providerId,
                        Protocol source, Protocol target, boolean stream, Kind kind, AttemptOutcome outcome, long nanos, Long firstTransportEventNanos, Long ttftNanos, Usage usage) {
        String[] tags = {"protocol", target.name(), "stream", Boolean.toString(stream), "kind", kind.name(), "outcome", outcome.name()};
        registry.counter("llm.gateway.provider.attempts", tags).increment();
        Timer.builder("llm.gateway.provider.duration").tags(tags).publishPercentileHistogram().register(registry).record(nanos, TimeUnit.NANOSECONDS);
        if (firstTransportEventNanos != null) Timer.builder("llm.gateway.provider.first.event").tag("protocol", target.name()).publishPercentileHistogram().register(registry).record(firstTransportEventNanos, TimeUnit.NANOSECONDS);
        if (ttftNanos != null) Timer.builder("llm.gateway.provider.ttft").tag("protocol", target.name()).publishPercentileHistogram().register(registry).record(ttftNanos, TimeUnit.NANOSECONDS);
        if (kind == Kind.RETRY) registry.counter("llm.gateway.retries").increment();
        if (kind == Kind.FALLBACK) registry.counter("llm.gateway.fallbacks").increment();
        if (kind == Kind.HEDGE) registry.counter("llm.gateway.hedges.started").increment();
        if (outcome.cancellation()) registry.counter("llm.gateway.attempt.cancellations", "reason", outcome.name()).increment();
        tokens("attempt", usage);
        LOG.atInfo().addKeyValue("requestId", requestId).addKeyValue("attemptId", attemptId).addKeyValue("virtualModelId", virtualModelId)
                .addKeyValue("bindingId", bindingId).addKeyValue("providerId", providerId).addKeyValue("sourceProtocol", source)
                .addKeyValue("targetProtocol", target).addKeyValue("attemptKind", kind).addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(nanos)).log("gateway_attempt_finished");
    }
    public void hedgeWon() { registry.counter("llm.gateway.hedges.won").increment(); }
    public void circuitTransition(String transition) {
        if (!transition.isEmpty()) {
            String[] states = transition.split(">");
            registry.counter("llm.gateway.circuit.transitions", "from", states[0], "to", states[1]).increment();
        }
    }
    public void circuitState(String bindingId, com.llmgateway.resilience.CircuitState state) {
        var gauge = circuitStates.computeIfAbsent(bindingId, id -> {
            var value = new java.util.concurrent.atomic.AtomicInteger();
            io.micrometer.core.instrument.Gauge.builder("llm.gateway.circuit.state", value, java.util.concurrent.atomic.AtomicInteger::get)
                    .tag("binding", id).register(registry);
            return value;
        });
        gauge.set(state == com.llmgateway.resilience.CircuitState.CLOSED ? 0 : state == com.llmgateway.resilience.CircuitState.HALF_OPEN ? 1 : 2);
    }
    public void removeCircuit(String bindingId) {
        circuitStates.remove(bindingId);
        registry.find("llm.gateway.circuit.state").tag("binding", bindingId).meters().forEach(registry::remove);
    }
    public void discovery(String outcome, long nanos) {
        registry.counter("llm.gateway.discovery.runs", "outcome", outcome).increment();
        registry.timer("llm.gateway.discovery.duration", "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
    }
    private void tokens(String scope, Usage usage) {
        if (usage == null) return;
        if (usage.inputTokens() != null) registry.counter("llm.gateway.tokens", "scope", scope, "direction", "input").increment(usage.inputTokens());
        if (usage.outputTokens() != null) registry.counter("llm.gateway.tokens", "scope", scope, "direction", "output").increment(usage.outputTokens());
    }
}
