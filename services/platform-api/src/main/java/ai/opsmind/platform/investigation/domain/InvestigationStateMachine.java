package ai.opsmind.platform.investigation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/**
 * Deterministic investigation reducer. It performs no I/O, clock reads, UUID generation,
 * provider calls, or persistence. Phase 9 can replace only the runner around this reducer.
 */
public final class InvestigationStateMachine {

    public enum Status {
        CREATED, ANALYZING, WAITING_FOR_EVIDENCE, COMPLETED, ABSTAINED,
        BUDGET_EXCEEDED, NO_PROGRESS, FAILED
    }

    public record State(
        UUID runId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        InvestigationCommand.Budget budget,
        Status status,
        int rounds,
        int toolCalls,
        int totalTokens,
        Set<String> requestedFingerprints,
        Set<UUID> evidenceIds,
        List<AnalysisRuntimeResponse.ToolIntent> pendingIntents,
        AnalysisRuntimeResponse finalResponse,
        String terminalReason,
        Instant startedAt,
        Instant deadlineAt,
        Instant endedAt
    ) {
        public State {
            requestedFingerprints = Set.copyOf(requestedFingerprints);
            evidenceIds = Set.copyOf(evidenceIds);
            pendingIntents = List.copyOf(pendingIntents);
        }
    }

    public record Step(State state, List<InvestigationEvent> events) {
        public Step {
            events = List.copyOf(events);
        }
    }

    private InvestigationStateMachine() { }

    public static Step start(InvestigationCommand.Start command) {
        require(command.runId(), "runId");
        require(command.organizationId(), "organizationId");
        require(command.projectId(), "projectId");
        require(command.incidentId(), "incidentId");
        require(command.startedAt(), "startedAt");
        require(command.deadlineAt(), "deadlineAt");
        if (!command.deadlineAt().isAfter(command.startedAt())) {
            throw new IllegalArgumentException("Investigation deadline must follow the start time.");
        }
        State state = new State(
            command.runId(), command.organizationId(), command.projectId(), command.incidentId(),
            command.budget(), Status.CREATED, 0, 0, 0, Set.of(), Set.of(), List.of(), null,
            null, command.startedAt(), command.deadlineAt(), null
        );
        return new Step(state, List.of(new InvestigationEvent.RunStarted(
            command.runId(), command.incidentId(), command.budget(), command.startedAt())));
    }

    public static Step apply(State state, InvestigationCommand command, Instant occurredAt) {
        require(state, "state");
        require(command, "command");
        require(occurredAt, "occurredAt");
        if (terminal(state.status())) return new Step(state, List.of());
        return switch (command) {
            case InvestigationCommand.AnalysisReceived received ->
                InvestigationTransitions.analysis(state, received.response(), occurredAt);
            case InvestigationCommand.ToolEvidenceReceived received ->
                InvestigationTransitions.evidence(state, received, occurredAt);
            case InvestigationCommand.Failed failed ->
                InvestigationTransitions.fail(state, failed.reason(), occurredAt);
            case InvestigationCommand.Start ignored -> throw new IllegalArgumentException(
                "A started investigation cannot be started again."
            );
        };
    }

    private static boolean terminal(Status status) {
        return switch (status) {
            case COMPLETED, ABSTAINED, BUDGET_EXCEEDED, NO_PROGRESS, FAILED -> true;
            default -> false;
        };
    }

    private static <T> void require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required.");
    }
}
