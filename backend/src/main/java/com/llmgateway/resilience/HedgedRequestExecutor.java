package com.llmgateway.resilience;

import com.llmgateway.health.AttemptOutcome;
import com.llmgateway.inference.*;
import com.llmgateway.model.RoutingPolicy;
import com.llmgateway.routing.RoutingCandidate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Consumer;
import org.reactivestreams.Subscription;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Single subscription per attempt, bounded preface/egress queues, one shared retry/fallback/hedge scheduler. */
@Component
public class HedgedRequestExecutor {
    private final Scheduler scheduler;
    public HedgedRequestExecutor() { this(Schedulers.parallel()); }
    public HedgedRequestExecutor(Scheduler scheduler) { this.scheduler = scheduler; }
    public enum Kind { FIRST, RETRY, FALLBACK, HEDGE }
    public static final class Cancellation {
        private volatile AttemptOutcome outcome = AttemptOutcome.CLIENT_CANCELLED;
        public AttemptOutcome outcome() { return outcome; }
        void lose() { outcome = AttemptOutcome.HEDGE_CANCELLED; }
        void timeout() { outcome = AttemptOutcome.TIMEOUT; }
    }
    @FunctionalInterface public interface Attempt<T> { Flux<T> start(RoutingCandidate candidate, Kind kind, Cancellation cancellation); }
    public <T> Flux<T> execute(List<RoutingCandidate> candidates, RoutingPolicy policy, AttemptBudget budget,
                               Predicate<T> meaningful, Attempt<T> attempt) {
        return execute(candidates, policy, budget, meaningful, attempt, ignored -> {});
    }
    public <T> Flux<T> execute(List<RoutingCandidate> candidates, RoutingPolicy policy, AttemptBudget budget,
                               Predicate<T> meaningful, Attempt<T> attempt, Consumer<Kind> won) {
        return Flux.create(sink -> new Coordinator<>(sink, candidates, policy, budget, meaningful, attempt, won).start(), FluxSink.OverflowStrategy.ERROR);
    }
    private final class Coordinator<T> {
        private final FluxSink<T> sink;
        private final List<RoutingCandidate> candidates;
        private final RoutingPolicy policy;
        private final AttemptBudget budget;
        private final Predicate<T> meaningful;
        private final Attempt<T> attempt;
        private final Consumer<Kind> won;
        private final List<GatewayException> failures = new ArrayList<>();
        private final List<Branch> branches = new ArrayList<>();
        private final List<Disposable> timers = new ArrayList<>();
        private final ArrayDeque<T> queue = new ArrayDeque<>();
        private final Set<String> destinations = new HashSet<>();
        private Branch winner;
        private int next;
        private int active;
        private int hedges;
        private boolean stopped;
        private boolean completed;
        private boolean draining;
        Coordinator(FluxSink<T> sink, List<RoutingCandidate> candidates, RoutingPolicy policy, AttemptBudget budget,
                    Predicate<T> meaningful, Attempt<T> attempt, Consumer<Kind> won) {
            this.sink = sink; this.candidates = candidates; this.policy = policy; this.budget = budget; this.meaningful = meaningful; this.attempt = attempt;
            this.won = won;
        }
        synchronized void start() {
            sink.onCancel(this::cancelAll);
            sink.onDispose(this::cancelAll);
            sink.onRequest(ignored -> drain());
            if (!budget.available()) { fail(new GatewayException(GatewayError.TIMEOUT)); return; }
            timers.add(scheduler.schedule(() -> deadline(), budget.remaining().toMillis(), TimeUnit.MILLISECONDS));
            startNext(Kind.FIRST);
            scheduleHedge();
        }
        private synchronized void deadline() { if (!stopped) fail(new GatewayException(GatewayError.TIMEOUT)); }
        private synchronized void scheduleHedge() {
            if (!policy.hedgingEnabled() || !policy.execution().allowReplay() || hedges >= policy.maxHedgeCount()
                    || winner != null || stopped || next >= candidates.size() || !budget.available()) return;
            Duration delay = policy.hedgeDelay();
            if (budget.remaining().compareTo(delay) <= 0) return;
            timers.add(scheduler.schedule(() -> {
                synchronized (Coordinator.this) {
                    if (stopped || winner != null || !budget.available()) return;
                    hedges++; startNext(Kind.HEDGE); scheduleHedge();
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS));
        }
        private void startNext(Kind kind) {
            if (stopped || winner != null) return;
            while (next < candidates.size()) {
                RoutingCandidate candidate = candidates.get(next++);
                if (!destinations.add(candidate.destination())) continue;
                if (!budget.available()) break;
                Branch branch = new Branch(candidate); branches.add(branch); launch(branch, kind); return;
            }
            if (active == 0) fail(RetryPolicy.exhausted(failures, budget));
        }
        private void launch(Branch branch, Kind kind) {
            if (stopped || winner != null || !budget.available()) { if (active == 0 && winner == null) fail(RetryPolicy.exhausted(failures, budget)); return; }
            branch.preface.clear(); branch.pending = false; branch.finished = false; active++;
            if (branch.origin == null) branch.origin = kind;
            branch.cancellation = new Cancellation();
            branch.subscriber = new BaseSubscriber<>() {
                @Override protected void hookOnSubscribe(Subscription subscription) { request(1); }
                @Override protected void hookOnNext(T value) { receive(branch, value); }
                @Override protected void hookOnError(Throwable error) { failed(branch, GatewayException.safe(error)); }
                @Override protected void hookOnComplete() { finished(branch); }
            };
            try { attempt.start(branch.candidate, kind, branch.cancellation).subscribe(branch.subscriber); }
            catch (Throwable failure) { failed(branch, GatewayException.safe(failure)); }
        }
        private synchronized void receive(Branch branch, T value) {
            if (stopped || branch.finished || winner != null && winner != branch) return;
            if (winner == null) {
                if (branch.preface.size() >= 32) { branch.subscriber.cancel(); failed(branch, GatewayException.response()); return; }
                branch.preface.add(value);
                if (!meaningful.test(value)) { branch.subscriber.request(1); return; }
                winner = branch;
                won.accept(branch.origin);
                for (Branch other : branches) if (other != branch) other.cancel(true);
                timers.forEach(Disposable::dispose);
                timers.clear();
                // Keep the total deadline active after the winner has been selected.
                timers.add(scheduler.schedule(() -> deadline(), Math.max(1, budget.remaining().toMillis()), TimeUnit.MILLISECONDS));
                queue.addAll(branch.preface); branch.preface.clear();
            } else queue.add(value);
            if (queue.size() > 64) { fail(GatewayException.response()); return; }
            branch.pending = true; drain();
        }
        private synchronized void drain() {
            if (stopped || draining) return;
            draining = true;
            try {
                for (;;) {
                    while (!queue.isEmpty() && sink.requestedFromDownstream() > 0 && !sink.isCancelled()) sink.next(queue.removeFirst());
                    if (completed && queue.isEmpty()) { stopped = true; sink.complete(); cancelResources(); return; }
                    if (winner == null || !winner.pending || completed || !queue.isEmpty() || sink.requestedFromDownstream() == 0) return;
                    winner.pending = false;
                    winner.subscriber.request(1);
                    if (queue.isEmpty() && !completed) return;
                }
            } finally { draining = false; }
        }
        private synchronized void finished(Branch branch) {
            if (stopped || branch.finished) return;
            branch.finished = true; active--;
            if (winner == branch) { completed = true; drain(); }
            else if (winner == null) failedEmpty(branch);
        }
        private void failedEmpty(Branch branch) { failures.add(GatewayException.response()); startNext(Kind.FALLBACK); }
        private synchronized void failed(Branch branch, GatewayException failure) {
            if (stopped || branch.finished || winner != null && winner != branch) return;
            branch.finished = true; active--;
            if (winner == branch) { fail(failure); return; }
            failures.add(failure); branch.preface.clear();
            if (!RetryPolicy.fallback(failure, policy.execution()) && !RetryPolicy.retry(failure, policy.execution())) {
                next = candidates.size();
                if (active == 0) fail(failure);
                return;
            }
            if (branch.retries < branch.candidate.provider().maxRetries() && RetryPolicy.retry(failure, policy.execution()) && budget.available()) {
                Duration delay = RetryPolicy.delay(++branch.retries, failure, policy.execution(), Math.random());
                if (budget.remaining().compareTo(delay) <= 0) { startNext(Kind.FALLBACK); return; }
                active++; // A scheduled retry counts as pending work but does not consume a physical attempt.
                timers.add(scheduler.schedule(() -> {
                    synchronized (Coordinator.this) { active--; if (!stopped && winner == null) launch(branch, Kind.RETRY); }
                }, delay.toMillis(), TimeUnit.MILLISECONDS));
            } else startNext(Kind.FALLBACK);
        }
        private void fail(GatewayException failure) {
            if (stopped) return;
            if (failure.error() == GatewayError.TIMEOUT) branches.forEach(b -> { if (b.cancellation != null) b.cancellation.timeout(); });
            stopped = true; cancelResources(); sink.error(failure);
        }
        private synchronized void cancelAll() {
            if (budget.remaining().isZero()) branches.forEach(b -> { if (b.cancellation != null) b.cancellation.timeout(); });
            stopped = true; cancelResources();
        }
        private void cancelResources() { timers.forEach(Disposable::dispose); branches.forEach(b -> b.cancel(false)); queue.clear(); }
        private final class Branch {
            final RoutingCandidate candidate;
            final List<T> preface = new ArrayList<>();
            BaseSubscriber<T> subscriber;
            Cancellation cancellation;
            int retries;
            Kind origin;
            boolean pending;
            boolean finished;
            Branch(RoutingCandidate candidate) { this.candidate = candidate; }
            void cancel(boolean loser) {
                if (loser && cancellation != null) cancellation.lose();
                if (subscriber != null && !finished) { subscriber.cancel(); finished = true; }
            }
        }
    }
}
