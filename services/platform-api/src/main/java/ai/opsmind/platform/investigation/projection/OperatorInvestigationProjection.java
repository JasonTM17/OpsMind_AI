package ai.opsmind.platform.investigation.projection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.OperatorDisplayRedactor;
import ai.opsmind.platform.common.api.OperatorProjection;

public final class OperatorInvestigationProjection {

    private static final String MODEL_ALIAS = "platform-analysis-adapter";
    private static final String PROMPT_VERSION = "prompt-incident-investigation-v1";
    private static final String WITHHELD_EXPLANATION =
        "Model-authored explanation withheld; inspect the cited durable evidence.";
    private static final String WITHHELD_RATIONALE =
        "Model-authored rationale withheld by display policy.";

    private OperatorInvestigationProjection() { }

    public static OperatorProjection<InvestigationRunReadModel> from(
        InvestigationRunReadModel source
    ) {
        Counter counter = new Counter();
        List<AnalysisRuntimeResponse.ToolIntent> pending = source.pendingToolCalls().stream()
            .map(intent -> safeIntent(intent, counter))
            .toList();
        AnalysisRuntimeResponse analysis = source.analysis() == null
            ? null
            : safeAnalysis(source.analysis(), counter);
        OperatorDisplayRedactor.Redaction terminalReason = source.terminalReason() == null
            ? new OperatorDisplayRedactor.Redaction("", 0)
            : new OperatorDisplayRedactor().redact(
                source.terminalReason(), "Investigation stopped safely."
            );
        counter.add(terminalReason.count());
        InvestigationRunReadModel body = new InvestigationRunReadModel(
            source.runId(), source.organizationId(), source.projectId(), source.incidentId(),
            source.status(), source.budget(), source.rounds(), source.toolCalls(),
            source.totalTokens(), source.evidenceIds(), pending, analysis,
            terminalReason.value().isEmpty() ? null : terminalReason.value(),
            source.startedAt(), source.deadlineAt(), source.endedAt()
        );
        return new OperatorProjection<>(body, counter.value());
    }

    private static AnalysisRuntimeResponse safeAnalysis(
        AnalysisRuntimeResponse source,
        Counter counter
    ) {
        if (!PROMPT_VERSION.equals(source.promptVersion())) {
            throw new IllegalStateException("Investigation prompt contract is not display-approved.");
        }
        Map<AnalysisRuntimeResponse.Citation, Integer> citationIndexes = new LinkedHashMap<>();
        List<AnalysisRuntimeResponse.Citation> citations = new ArrayList<>();
        for (AnalysisRuntimeResponse.Citation citation : source.citations()) {
            int index = citationIndexes.computeIfAbsent(citation, ignored -> citationIndexes.size());
            citations.add(safeCitation(citation, index, counter));
        }
        List<AnalysisRuntimeResponse.Hypothesis> hypotheses = new ArrayList<>();
        for (int index = 0; index < source.hypotheses().size(); index++) {
            AnalysisRuntimeResponse.Hypothesis hypothesis = source.hypotheses().get(index);
            List<AnalysisRuntimeResponse.Citation> hypothesisCitations = hypothesis.citations()
                .stream()
                .map(citation -> {
                    Integer citationIndex = citationIndexes.get(citation);
                    if (citationIndex == null) {
                        throw new IllegalStateException(
                            "Investigation hypothesis citation is not display-bound."
                        );
                    }
                    return safeCitation(citation, citationIndex, counter);
                })
                .toList();
            String title = "Evidence-backed hypothesis " + (index + 1);
            counter.changed(hypothesis.title(), title);
            counter.changed(hypothesis.explanation(), WITHHELD_EXPLANATION);
            hypotheses.add(new AnalysisRuntimeResponse.Hypothesis(
                title, WITHHELD_EXPLANATION, hypothesis.confidence(), hypothesisCitations
            ));
        }
        counter.changed(source.modelId(), MODEL_ALIAS);
        return new AnalysisRuntimeResponse(
            source.status(), source.runId(), MODEL_ALIAS, PROMPT_VERSION, source.schemaVersion(),
            hypotheses,
            controlledNotes(source.counterEvidence(), "Counter-evidence note", counter),
            controlledNotes(source.missingEvidence(), "Evidence-gap note", counter),
            citations, source.confidence(), source.usage(), source.costEstimate(),
            source.requestedToolCalls().stream().map(intent -> safeIntent(intent, counter)).toList()
        );
    }

    private static AnalysisRuntimeResponse.Citation safeCitation(
        AnalysisRuntimeResponse.Citation source,
        int index,
        Counter counter
    ) {
        String claim = "Platform-verified durable evidence citation " + (index + 1) + ".";
        counter.changed(source.claim(), claim);
        return new AnalysisRuntimeResponse.Citation(source.evidenceId(), source.digest(), claim);
    }

    private static List<String> controlledNotes(
        List<String> source,
        String label,
        Counter counter
    ) {
        List<String> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            String replacement = label + " " + (index + 1) + " withheld by display policy.";
            counter.changed(source.get(index), replacement);
            result.add(replacement);
        }
        return List.copyOf(result);
    }

    private static AnalysisRuntimeResponse.ToolIntent safeIntent(
        AnalysisRuntimeResponse.ToolIntent source,
        Counter counter
    ) {
        if (!"metrics".equals(source.connector()) || !"query".equals(source.operation())) {
            throw new IllegalStateException("Investigation tool intent is not display-approved.");
        }
        counter.changed(source.rationale(), WITHHELD_RATIONALE);
        return new AnalysisRuntimeResponse.ToolIntent(
            source.intentId(), source.connector(), source.operation(),
            source.argumentsDigest(), WITHHELD_RATIONALE
        );
    }

    private static final class Counter {
        private int value;

        void changed(String source, String replacement) {
            if (!replacement.equals(source)) {
                value = Math.addExact(value, 1);
            }
        }

        void add(int amount) {
            value = Math.addExact(value, amount);
        }

        int value() {
            return value;
        }
    }
}
