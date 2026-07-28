package ai.opsmind.platform.investigation.application;

import java.util.Optional;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

public interface DurableInvestigationAdmissionRepository {

    Optional<InvestigationStateMachine.State> loadExisting(
        InvestigationCommand.Start command,
        InvestigationExecutionContext context
    );

    InvestigationStateMachine.State createOrLoad(
        InvestigationCommand.Start command,
        InvestigationExecutionContext context
    );
}
