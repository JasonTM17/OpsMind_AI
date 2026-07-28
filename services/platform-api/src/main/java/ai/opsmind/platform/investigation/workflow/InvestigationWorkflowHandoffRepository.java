package ai.opsmind.platform.investigation.workflow;

import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import java.util.Optional;

public interface InvestigationWorkflowHandoffRepository {

    Optional<InvestigationStateMachine.State> loadExisting(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorizedIncident
    );

    InvestigationStateMachine.State createOrLoad(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorizedIncident
    );
}
