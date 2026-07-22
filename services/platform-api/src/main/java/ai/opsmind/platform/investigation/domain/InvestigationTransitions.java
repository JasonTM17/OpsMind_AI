package ai.opsmind.platform.investigation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine.State;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine.Status;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine.Step;

final class InvestigationTransitions {

    private static final Set<String> SOURCE_TYPES = Set.of(
        "metric", "log_summary", "trace", "change", "runbook"
    );

    private InvestigationTransitions() { }

    static Step analysis(State state, AnalysisRuntimeResponse response, Instant occurredAt) {
        if (response == null || !state.runId().equals(response.runId())) {
            return fail(state, "AI Runtime response does not match the investigation run.", occurredAt);
        }
        int tokens = state.totalTokens() + response.usage().totalTokens();
        int rounds = state.rounds() + 1;
        List<InvestigationEvent> events = new ArrayList<>();
        events.add(new InvestigationEvent.AnalysisAccepted(
            state.runId(), response.status(), rounds, tokens, occurredAt));
        State analyzed = copy(
            state, state.status(), rounds, state.toolCalls(), tokens,
            state.requestedFingerprints(), state.pendingIntents(), null, null, null
        );
        if (rounds > state.budget().maxRounds() || tokens > state.budget().maxTokens()) {
            return terminal(analyzed, Status.BUDGET_EXCEEDED, "Investigation budget exhausted.", occurredAt,
                new InvestigationEvent.BudgetExceeded(state.runId(), "Investigation budget exhausted.", occurredAt),
                events);
        }
        if ("complete".equals(response.status())) {
            if (response.citations().stream()
                .anyMatch(citation -> !state.evidenceIds().contains(citation.evidenceId()))) {
                return terminal(analyzed, Status.ABSTAINED,
                    "Final analysis contains an unpersisted citation.", occurredAt,
                    new InvestigationEvent.Abstained(state.runId(),
                        "Final analysis contains an unpersisted citation.", occurredAt), events);
            }
            State completed = copy(state, Status.COMPLETED, rounds, state.toolCalls(), tokens,
                Set.of(), List.of(), response, null, occurredAt);
            events.add(new InvestigationEvent.Completed(state.runId(), response, occurredAt));
            return new Step(completed, events);
        }
        if ("abstain".equals(response.status())) {
            return terminal(analyzed, Status.ABSTAINED, "AI Runtime abstained.", occurredAt,
                new InvestigationEvent.Abstained(state.runId(), "AI Runtime abstained.", occurredAt), events);
        }
        if ("provider_unavailable".equals(response.status())) {
            return terminal(analyzed, Status.FAILED, "AI Runtime is unavailable.", occurredAt,
                new InvestigationEvent.Failed(state.runId(), "AI Runtime is unavailable.", occurredAt), events);
        }
        if ("budget_exceeded".equals(response.status())) {
            return terminal(analyzed, Status.BUDGET_EXCEEDED, "AI Runtime reported budget exhaustion.", occurredAt,
                new InvestigationEvent.BudgetExceeded(state.runId(),
                    "AI Runtime reported budget exhaustion.", occurredAt), events);
        }
        if (response.requestedToolCalls().isEmpty()) {
            return terminal(analyzed, Status.NO_PROGRESS,
                "AI Runtime requested more evidence without an executable read intent.", occurredAt,
                new InvestigationEvent.NoProgress(state.runId(),
                    "AI Runtime requested more evidence without an executable read intent.", occurredAt), events);
        }
        Set<String> fingerprints = new HashSet<>(state.requestedFingerprints());
        for (AnalysisRuntimeResponse.ToolIntent intent : response.requestedToolCalls()) {
            if (!fingerprints.add(fingerprint(intent))) {
                return terminal(analyzed, Status.NO_PROGRESS, "The same tool intent was requested twice.", occurredAt,
                    new InvestigationEvent.NoProgress(state.runId(),
                        "The same tool intent was requested twice.", occurredAt), events);
            }
        }
        if (state.toolCalls() + response.requestedToolCalls().size() > state.budget().maxToolCalls()) {
            return terminal(analyzed, Status.BUDGET_EXCEEDED, "Tool-call budget exhausted.", occurredAt,
                new InvestigationEvent.BudgetExceeded(state.runId(), "Tool-call budget exhausted.", occurredAt),
                events);
        }
        State waiting = copy(state, Status.WAITING_FOR_EVIDENCE, rounds,
            state.toolCalls() + response.requestedToolCalls().size(), tokens, fingerprints,
            response.requestedToolCalls(), null, null, null);
        events.add(new InvestigationEvent.ToolRequested(
            state.runId(), response.requestedToolCalls(), occurredAt));
        return new Step(waiting, events);
    }

    static Step evidence(
        State state,
        InvestigationCommand.ToolEvidenceReceived command,
        Instant occurredAt
    ) {
        if (state.status() != Status.WAITING_FOR_EVIDENCE || command.intentId() == null
            || state.pendingIntents().stream().noneMatch(intent -> command.intentId().equals(intent.intentId()))) {
            return fail(state, "Tool evidence does not match a pending read-only intent.", occurredAt);
        }
        if (command.evidenceId() == null || command.digest() == null
            || !command.digest().matches("sha256:[0-9a-f]{64}")
            || !SOURCE_TYPES.contains(command.sourceType())) {
            return fail(state, "Tool evidence metadata is invalid.", occurredAt);
        }
        if (state.evidenceIds().contains(command.evidenceId())) {
            return terminal(state, Status.NO_PROGRESS, "Duplicate evidence was returned.", occurredAt,
                new InvestigationEvent.NoProgress(state.runId(), "Duplicate evidence was returned.", occurredAt));
        }
        if (state.evidenceIds().size() >= state.budget().maxEvidenceItems()) {
            return terminal(state, Status.BUDGET_EXCEEDED, "Evidence-item budget exhausted.", occurredAt,
                new InvestigationEvent.BudgetExceeded(state.runId(),
                    "Evidence-item budget exhausted.", occurredAt));
        }
        Set<UUID> evidenceIds = new HashSet<>(state.evidenceIds());
        evidenceIds.add(command.evidenceId());
        List<AnalysisRuntimeResponse.ToolIntent> pending = state.pendingIntents().stream()
            .filter(intent -> !command.intentId().equals(intent.intentId())).toList();
        State next = new State(
            state.runId(), state.organizationId(), state.projectId(), state.incidentId(), state.actorId(),
            state.budget(), pending.isEmpty() ? Status.ANALYZING : Status.WAITING_FOR_EVIDENCE,
            state.revision(), state.eventCount(),
            state.rounds(), state.toolCalls(), state.totalTokens(), state.requestedFingerprints(),
            evidenceIds, pending, state.finalResponse(), null, state.startedAt(), state.deadlineAt(), null
        );
        return new Step(next, List.of(new InvestigationEvent.EvidenceAppended(
            state.runId(), command.intentId(), command.evidenceId(), command.digest(),
            command.sourceType(), occurredAt)));
    }

    static Step fail(State state, String reason, Instant occurredAt) {
        String safeReason = reason == null || reason.isBlank() ? "Investigation failed." : reason;
        return terminal(state, Status.FAILED, safeReason, occurredAt,
            new InvestigationEvent.Failed(state.runId(), safeReason, occurredAt));
    }

    private static Step terminal(
        State state, Status status, String reason, Instant endedAt, InvestigationEvent event
    ) {
        return terminal(state, status, reason, endedAt, event, List.of());
    }

    private static Step terminal(
        State state,
        Status status,
        String reason,
        Instant endedAt,
        InvestigationEvent event,
        List<InvestigationEvent> preceding
    ) {
        List<InvestigationEvent> events = new ArrayList<>(preceding);
        events.add(event);
        return new Step(copy(state, status, state.rounds(), state.toolCalls(), state.totalTokens(),
            state.requestedFingerprints(), List.of(), state.finalResponse(), reason, endedAt), events);
    }

    private static State copy(
        State state, Status status, int rounds, int toolCalls, int totalTokens,
        Set<String> fingerprints, List<AnalysisRuntimeResponse.ToolIntent> pending,
        AnalysisRuntimeResponse response, String reason, Instant endedAt
    ) {
        return new State(state.runId(), state.organizationId(), state.projectId(), state.incidentId(),
            state.actorId(), state.budget(), status, state.revision(), state.eventCount(), rounds,
            toolCalls, totalTokens, fingerprints, state.evidenceIds(), pending, response, reason,
            state.startedAt(), state.deadlineAt(), endedAt);
    }

    private static String fingerprint(AnalysisRuntimeResponse.ToolIntent intent) {
        return intent.connector() + ":" + intent.operation() + ":" + intent.argumentsDigest();
    }
}
