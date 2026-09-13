package com.llmgateway.resilience;

import com.llmgateway.inference.*;
import com.llmgateway.model.ExecutionPolicy;
import java.time.Duration;
import java.util.List;

public final class RetryPolicy {
    private RetryPolicy() {}
    public static boolean retry(GatewayException failure, ExecutionPolicy policy) {
        if (failure.possiblySent() && !policy.allowReplay()) return false;
        return switch (failure.error()) {
            case CONNECTION_FAILED, NETWORK_ERROR, TIMEOUT, RATE_LIMITED, PROVIDER_ERROR -> true;
            default -> false;
        };
    }
    public static boolean fallback(GatewayException failure, ExecutionPolicy policy) {
        if (failure.error() == GatewayError.PROVIDER_AUTHENTICATION || failure.error() == GatewayError.PROVIDER_MODEL_NOT_FOUND
                || failure.error() == GatewayError.CREDENTIAL_CONFIGURATION || failure.error() == GatewayError.CONFIGURATION_CHANGED
                || failure.error() == GatewayError.NO_CANDIDATES) return true;
        if (failure.possiblySent() && !policy.allowReplay()) return false;
        return retry(failure, policy) || failure.error() == GatewayError.INVALID_RESPONSE;
    }
    public static Duration delay(int retry, GatewayException error, ExecutionPolicy policy, double random) {
        long exponential = Math.min(policy.maxBackoffMs(), policy.backoffMs() * (1L << Math.min(20, Math.max(0, retry - 1))));
        double multiplier = 1 + policy.jitter() * (2 * Math.min(1, Math.max(0, random)) - 1);
        long backoff = Math.min(policy.maxBackoffMs(), Math.max(0, Math.round(exponential * multiplier)));
        return Duration.ofMillis(Math.max(Math.max(backoff, error.error() == GatewayError.RATE_LIMITED ? 1000 : 0), Math.min(60_000, error.retryAfter().toMillis())));
    }
    public static GatewayException exhausted(List<GatewayException> failures, AttemptBudget budget) {
        if (budget.remaining().isZero()) return new GatewayException(GatewayError.TIMEOUT);
        if (failures.isEmpty()) return new GatewayException(GatewayError.NO_CANDIDATES);
        if (failures.stream().allMatch(e -> e.error() == GatewayError.RATE_LIMITED)) return new GatewayException(GatewayError.RATE_LIMITED, true,
                Duration.ofMillis(Math.min(60_000, Math.max(1000, failures.stream().mapToLong(e -> e.retryAfter().toMillis()).max().orElse(1000)))));
        if (failures.stream().anyMatch(e -> e.error() == GatewayError.PROVIDER_AUTHENTICATION || e.error() == GatewayError.INVALID_RESPONSE
                || e.error() == GatewayError.CREDENTIAL_CONFIGURATION || e.error() == GatewayError.PROVIDER_MODEL_NOT_FOUND)) return new GatewayException(GatewayError.PROVIDER_ERROR);
        if (failures.stream().anyMatch(e -> e.error() == GatewayError.TIMEOUT)) return new GatewayException(GatewayError.TIMEOUT);
        return new GatewayException(GatewayError.NO_CANDIDATES);
    }
}
