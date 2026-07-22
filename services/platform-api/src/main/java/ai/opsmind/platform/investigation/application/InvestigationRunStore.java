package ai.opsmind.platform.investigation.application;

import java.util.UUID;

import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

public interface InvestigationRunStore {

    void create(InvestigationStateMachine.Step initial);

    void save(InvestigationStateMachine.State previous, InvestigationStateMachine.Step next);

    InvestigationStateMachine.State require(UUID organizationId, UUID actorId, UUID runId);
}
