package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;

public final class InlineInvestigationExecutionStarter implements InvestigationExecutionStarter {

    private final InvestigationOrchestrator orchestrator;

    public InlineInvestigationExecutionStarter(InvestigationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public StartResult start(InvestigationCommand.Start command, InvestigationExecutionContext context) {
        InvestigationStartDeadlinePolicy.requireActive(command);
        return new StartResult(orchestrator.run(command, context), false);
    }
}
