package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowAdmission;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowProperties;

public final class DurableInvestigationExecutionStarter implements InvestigationExecutionStarter {

    private final InvestigationWorkflowAdmission admission;
    private final DurableInvestigationAdmissionRepository handoffRepository;
    private final InvestigationWorkflowProperties properties;

    public DurableInvestigationExecutionStarter(
        InvestigationWorkflowAdmission admission,
        DurableInvestigationAdmissionRepository handoffRepository,
        InvestigationWorkflowProperties properties
    ) {
        this.admission = admission;
        this.handoffRepository = handoffRepository;
        this.properties = properties;
    }

    @Override
    public StartResult start(InvestigationCommand.Start command, InvestigationExecutionContext context) {
        var existing = handoffRepository.loadExisting(command, context);
        if (existing.isPresent()) {
            return new StartResult(existing.get(), true);
        }
        InvestigationStartDeadlinePolicy.requireActive(command);
        admission.requireReady(properties);
        return new StartResult(handoffRepository.createOrLoad(command, context), true);
    }
}
