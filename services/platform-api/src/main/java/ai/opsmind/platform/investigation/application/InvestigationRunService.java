package ai.opsmind.platform.investigation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.investigation.api.StartInvestigationRequest;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.projection.InvestigationProjectionAssembler;
import ai.opsmind.platform.investigation.projection.InvestigationRunReadModel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "enabled", havingValue = "true")
public final class InvestigationRunService {

    private final IncidentAnalysisAuthorizer authorizer;
    private final InvestigationOrchestrator orchestrator;
    private final InvestigationRunStore store;
    private final InvestigationProjectionAssembler projections;
    private final Clock clock;

    public InvestigationRunService(
        IncidentAnalysisAuthorizer authorizer,
        InvestigationOrchestrator orchestrator,
        InvestigationRunStore store,
        InvestigationProjectionAssembler projections,
        Clock clock
    ) {
        this.authorizer = authorizer;
        this.orchestrator = orchestrator;
        this.store = store;
        this.projections = projections;
        this.clock = clock;
    }

    public InvestigationRunReadModel start(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        StartInvestigationRequest request
    ) {
        AuthorizedIncidentAnalysisEvidence authorized = authorizer.requireEvidence(
            principal, organizationId, projectId, incidentId
        );
        Instant now = Instant.now(clock);
        if (!request.deadlineAt().isAfter(now)) {
            throw new PlatformProblemException(
                HttpStatus.REQUEST_TIMEOUT, "investigation.deadline-exceeded", "The investigation deadline elapsed."
            );
        }
        InvestigationCommand.Start command = new InvestigationCommand.Start(
            request.runId(), organizationId, projectId, incidentId, authorized.actorId(),
            new InvestigationCommand.Budget(
                request.maxRounds(), request.maxToolCalls(), request.maxEvidenceItems(), request.maxTokens()
            ), now, request.deadlineAt()
        );
        return projections.assemble(orchestrator.run(
            command,
            new InvestigationExecutionContext(principal, authorized)
        ));
    }

    public InvestigationRunReadModel get(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId
    ) {
        UUID actorId = authorizer.requireReadAccess(
            principal, organizationId, projectId, incidentId
        );
        InvestigationStateMachine.State state = store.requireScoped(
            organizationId, projectId, incidentId, actorId, runId
        );
        return projections.assemble(state);
    }
}
