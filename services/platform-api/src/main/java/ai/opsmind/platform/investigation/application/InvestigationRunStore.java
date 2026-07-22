package ai.opsmind.platform.investigation.application;

import java.util.UUID;

import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

public interface InvestigationRunStore {

    void create(InvestigationStateMachine.State state);

    void save(InvestigationStateMachine.State state);

    InvestigationStateMachine.State require(UUID organizationId, UUID runId);
}
