package ai.opsmind.platform.investigation.projection;

import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.springframework.stereotype.Component;

@Component
public final class InvestigationProjectionAssembler {

    public InvestigationRunReadModel assemble(InvestigationStateMachine.State state) {
        return new InvestigationRunReadModel(
            state.runId(), state.organizationId(), state.projectId(), state.incidentId(), state.status(),
            new InvestigationRunReadModel.BudgetView(
                state.budget().maxRounds(), state.budget().maxToolCalls(),
                state.budget().maxEvidenceItems(), state.budget().maxTokens()
            ), state.rounds(), state.toolCalls(), state.totalTokens(),
            state.evidenceIds().stream().sorted().toList(), state.pendingIntents(), state.finalResponse(),
            state.terminalReason(), state.startedAt(), state.deadlineAt(), state.endedAt()
        );
    }
}
