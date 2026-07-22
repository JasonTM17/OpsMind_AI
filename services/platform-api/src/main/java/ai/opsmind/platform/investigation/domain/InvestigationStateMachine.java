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
        UUID actorId,
        InvestigationCommand.Budget budget,
        Status status,
        long revision,
        long eventCount,
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
            if (runId == null || organizationId == null || projectId == null || incidentId == null
                || actorId == null || budget == null || status == null || revision < 0 || eventCount < 1
                || rounds < 0 || toolCalls < 0 || totalTokens < 0 || startedAt == null
                || deadlineAt == null || !deadlineAt.isAfter(startedAt)) {
                throw new IllegalArgumentException("Investigation state metadata is invalid.");
            }
            requestedFingerprints = Set.copyOf(requestedFingerprints);
            evidenceIds = Set.copyOf(evidenceIds);
            pendingIntents = List.copyOf(pendingIntents);
            boolean isTerminal = terminal(status);
            if (isTerminal != (endedAt != null)
                || (status == Status.COMPLETED) != (finalResponse != null)) {
                throw new IllegalArgumentException("Investigation terminal state is invalid.");
            }
        }
    }

    public record Step(State state, List<InvestigationEvent> events) {
        public Step {
            require(state, "state");
            events = List.copyOf(events);
        }
    }

    private InvestigationStateMachine() { }

    public static Step start(InvestigationCommand.Start command) {
        require(command.runId(), "runId");
        require(command.organizationId(), "organizationId");
        require(command.projectId(), "projectId");
        require(command.incidentId(), "incidentId");
        require(command.actorId(), "actorId");
        require(command.startedAt(), "startedAt");
        require(command.deadlineAt(), "deadlineAt");
        if (!command.deadlineAt().isAfter(command.startedAt())) {
            throw new IllegalArgumentException("Investigation deadline must follow the start time.");
        }
        State state = new State(
            command.runId(), command.organizationId(), command.projectId(), command.incidentId(),
            command.actorId(), command.budget(), Status.CREATED, 0, 1, 0, 0, 0,
            Set.of(), Set.of(), List.of(), null,
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
        Step transition = switch (command) {
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
        State next = transition.state();
        State versioned = new State(
            next.runId(), next.organizationId(), next.projectId(), next.incidentId(), next.actorId(),
            next.budget(), next.status(), state.revision() + 1,
            state.eventCount() + transition.events().size(), next.rounds(), next.toolCalls(),
            next.totalTokens(), next.requestedFingerprints(), next.evidenceIds(),
            next.pendingIntents(), next.finalResponse(), next.terminalReason(), next.startedAt(),
            next.deadlineAt(), next.endedAt()
        );
        return new Step(versioned, transition.events());
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
