package com.llmgateway.resilience;

import com.llmgateway.inference.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class AttemptBudget {
    private final int maximum;
    private final Instant deadline;
    private final Clock clock;
    private final AtomicInteger attempts = new AtomicInteger();
    public AttemptBudget(int maximum, Instant deadline, Clock clock) { this.maximum = maximum; this.deadline = deadline; this.clock = clock; }
    public int acquire() {
        if (remaining().isZero()) throw new GatewayException(GatewayError.TIMEOUT);
        for (;;) {
            int used = attempts.get();
            if (used >= maximum) throw new GatewayException(GatewayError.NO_CANDIDATES);
            if (attempts.compareAndSet(used, used + 1)) return used + 1;
        }
    }
    public int used() { return attempts.get(); }
    public boolean available() { return attempts.get() < maximum && !remaining().isZero(); }
    public Duration remaining() { Duration value = Duration.between(clock.instant(), deadline); return value.isNegative() ? Duration.ZERO : value; }
}
