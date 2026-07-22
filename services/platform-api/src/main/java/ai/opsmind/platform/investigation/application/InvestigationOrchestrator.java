package ai.opsmind.platform.investigation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.InvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolGatewayClient;

/** Bounded in-process runner. Temporal can replace this adapter without replacing the reducer. */
public final class InvestigationOrchestrator {

    private final InvestigationRunStore store;
    private final InvestigationAiRuntimeClient aiRuntime;
    private final InvestigationToolGatewayClient toolGateway;
    private final Clock clock;

    public InvestigationOrchestrator(
        InvestigationRunStore store,
        InvestigationAiRuntimeClient aiRuntime,
        InvestigationToolGatewayClient toolGateway,
        Clock clock
    ) {
        this.store = store;
        this.aiRuntime = aiRuntime;
        this.toolGateway = toolGateway;
        this.clock = clock;
    }

    public InvestigationStateMachine.State run(InvestigationCommand.Start command) {
        InvestigationStateMachine.Step initial = InvestigationStateMachine.start(command);
        store.create(initial);
        InvestigationStateMachine.State state = initial.state();
        while (!terminal(state.status())) {
            if (!Instant.now(clock).isBefore(state.deadlineAt())) {
                return apply(state, new InvestigationCommand.Failed("Investigation deadline exceeded."));
            }
            AnalysisRuntimeResponse response;
            try {
                response = aiRuntime.analyze(state.runId(), state.evidenceIds(), state.rounds());
            }
            catch (RuntimeException exception) {
                return apply(state, new InvestigationCommand.Failed("AI Runtime dependency failed."));
            }
            state = apply(state, new InvestigationCommand.AnalysisReceived(response));
            while (state.status() == InvestigationStateMachine.Status.WAITING_FOR_EVIDENCE
                && !state.pendingIntents().isEmpty()) {
                AnalysisRuntimeResponse.ToolIntent intent = state.pendingIntents().get(0);
                InvestigationToolGatewayClient.ToolEvidence evidence;
                try {
                    evidence = toolGateway.execute(intent, new InvestigationToolGatewayClient.ToolExecutionContext(
                        state.organizationId(), state.projectId(), state.incidentId(), state.runId(),
                        state.actorId(), state.deadlineAt()
                    ));
                }
                catch (RuntimeException exception) {
                    return apply(state, new InvestigationCommand.Failed("Tool Gateway dependency failed."));
                }
                if (evidence == null) {
                    return apply(state, new InvestigationCommand.Failed(
                        "Tool Gateway returned invalid evidence."
                    ));
                }
                state = apply(state, new InvestigationCommand.ToolEvidenceReceived(
                    evidence.intentId(), evidence.evidenceId(), evidence.digest(), evidence.sourceType(),
                    evidence.collectedEvidence()
                ));
            }
            if (state.status() == InvestigationStateMachine.Status.WAITING_FOR_EVIDENCE
                && state.pendingIntents().isEmpty()) {
                return apply(state, new InvestigationCommand.Failed("Investigation made no evidence progress."));
            }
        }
        return state;
    }

    private InvestigationStateMachine.State apply(
        InvestigationStateMachine.State state,
        InvestigationCommand command
    ) {
        Instant now = Instant.now(clock);
        InvestigationStateMachine.Step step = InvestigationStateMachine.apply(state, command, now);
        store.save(state, step);
        return step.state();
    }

    private boolean terminal(InvestigationStateMachine.Status status) {
        return switch (status) {
            case COMPLETED, ABSTAINED, BUDGET_EXCEEDED, NO_PROGRESS, FAILED -> true;
            default -> false;
        };
    }
}
