package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class InvestigationWorkflowReconciliationMetrics {

    private static final List<String> OUTCOMES = List.of(
        "match",
        "absence_candidate",
        "verified_absence",
        "released",
        "mismatch",
        "retry",
        "blocked",
        "lease_lost",
        "exhausted"
    );

    private final MeterRegistry registry;
    private final Map<String, Counter> outcomeCounters;
    private final AtomicLong ready = new AtomicLong();
    private final AtomicLong observerReady = new AtomicLong();
    private final AtomicLong databaseReady = new AtomicLong();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong retentionIneligible = new AtomicLong();
    private final AtomicLong oldestPendingAgeMillis = new AtomicLong();

    public InvestigationWorkflowReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
        outcomeCounters = OUTCOMES.stream().collect(Collectors.toUnmodifiableMap(
            outcome -> outcome,
            outcome -> Counter.builder("opsmind.workflow.reconciliation.outcomes")
                .tag("outcome", outcome)
                .register(registry)
        ));
        gauge("opsmind.workflow.reconciliation.ready", ready, 1.0);
        gauge("opsmind.workflow.reconciliation.pending", pending, 1.0);
        gauge("opsmind.workflow.reconciliation.blocked", blocked, 1.0);
        gauge(
            "opsmind.workflow.reconciliation.retention.ineligible",
            retentionIneligible,
            1.0
        );
        gauge(
            "opsmind.workflow.reconciliation.oldest.pending.age.seconds",
            oldestPendingAgeMillis,
            0.001
        );
    }

    public <T> T observe(String operation, Supplier<T> request) {
        return Timer.builder("opsmind.workflow.reconciliation.observation.duration")
            .tag("operation", operation)
            .register(registry)
            .record(request);
    }

    public void updateObserverReady(boolean observerReady) {
        this.observerReady.set(observerReady ? 1 : 0);
        updateReady();
    }

    public void updateDatabaseReady(boolean databaseReady) {
        this.databaseReady.set(databaseReady ? 1 : 0);
        updateReady();
    }

    public void updateStatus(InvestigationWorkflowReconciliationStatus status) {
        pending.set(status.pendingCount());
        blocked.set(status.blockedCount() + status.exhaustedCount());
        retentionIneligible.set(status.retentionIneligibleCount());
        oldestPendingAgeMillis.set(Math.round(status.oldestPendingAgeSeconds() * 1_000));
        updateDatabaseReady(true);
    }

    public void recordOutcome(String outcome, Duration convergence) {
        Counter counter = outcomeCounters.get(outcome);
        if (counter == null) {
            throw new IllegalArgumentException(
                "Workflow reconciliation metric outcome is outside policy."
            );
        }
        counter.increment();
        String terminalResult = terminalResult(outcome);
        if (terminalResult != null) {
            Timer.builder("opsmind.workflow.reconciliation.convergence.duration")
                .tag("result", terminalResult)
                .register(registry)
                .record(convergence.isNegative() ? Duration.ZERO : convergence);
        }
    }

    private void gauge(
        String name,
        AtomicLong value,
        double scale
    ) {
        Gauge.builder(name, value, current -> current.doubleValue() * scale)
            .register(registry);
    }

    private static String terminalResult(String outcome) {
        return switch (outcome) {
            case "match" -> "started";
            case "verified_absence", "mismatch" -> "rejected";
            case "blocked", "exhausted" -> "blocked";
            default -> null;
        };
    }

    private void updateReady() {
        ready.set(observerReady.get() == 1 && databaseReady.get() == 1 ? 1 : 0);
    }
}
