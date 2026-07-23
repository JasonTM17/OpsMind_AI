package ai.opsmind.platform.investigation.integration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/** Fail-closed mapping from untrusted model selectors to trusted invocation templates. */
public final class InvestigationToolIntentCatalog {

    private final Map<Selector, InvestigationToolInvocation> invocations;

    public InvestigationToolIntentCatalog(Collection<InvestigationToolInvocation> values) {
        if (values == null || values.isEmpty() || values.size() > 20) {
            throw new IllegalArgumentException("Investigation intent catalog is empty or oversized.");
        }
        Map<Selector, InvestigationToolInvocation> indexed = new LinkedHashMap<>();
        for (InvestigationToolInvocation invocation : values) {
            if (invocation == null || indexed.putIfAbsent(selector(invocation), invocation) != null) {
                throw new IllegalStateException("Investigation intent catalog contains a duplicate selector.");
            }
        }
        invocations = Map.copyOf(indexed);
    }

    public InvestigationToolInvocation resolve(AnalysisRuntimeResponse.ToolIntent intent) {
        if (intent == null) throw denied();
        InvestigationToolInvocation invocation = invocations.get(new Selector(
            intent.connector(), intent.operation(), intent.argumentsDigest()
        ));
        if (invocation == null) throw denied();
        return invocation;
    }

    public List<Selector> publicSelectors() {
        return invocations.keySet().stream()
            .sorted(java.util.Comparator.comparing(Selector::connector)
                .thenComparing(Selector::operation)
                .thenComparing(Selector::argumentsDigest))
            .toList();
    }

    private Selector selector(InvestigationToolInvocation invocation) {
        return new Selector(
            invocation.connector(), invocation.operation(), invocation.argumentsDigest()
        );
    }

    private IllegalArgumentException denied() {
        return new IllegalArgumentException("Investigation tool intent is not allowlisted.");
    }

    public record Selector(String connector, String operation, String argumentsDigest) { }
}
