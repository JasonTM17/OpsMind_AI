package ai.opsmind.platform.investigation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.investigation.api.StartInvestigationRequest;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.projection.InvestigationProjectionAssembler;
import ai.opsmind.platform.investigation.projection.InvestigationRunReadModel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "enabled", havingValue = "true")
public final class InvestigationRunService {

    private final IncidentAnalysisAuthorizer authorizer;
    private final InvestigationExecutionStarter executionStarter;
    private final InvestigationRunStore store;
    private final InvestigationProjectionAssembler projections;
    private final Clock clock;

    public InvestigationRunService(
        IncidentAnalysisAuthorizer authorizer,
        InvestigationExecutionStarter executionStarter,
        InvestigationRunStore store,
        InvestigationProjectionAssembler projections,
        Clock clock
    ) {
        this.authorizer = authorizer;
        this.executionStarter = executionStarter;
        this.store = store;
        this.projections = projections;
        this.clock = clock;
    }

    public InvestigationStartResult start(
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
        InvestigationCommand.Start command = startCommand(
            request, organizationId, projectId, incidentId, authorized.actorId(), now
        );
        InvestigationExecutionStarter.StartResult started = executionStarter.start(
            command,
            new InvestigationExecutionContext(principal, authorized)
        );
        return new InvestigationStartResult(
            projections.assemble(started.state()),
            started.asynchronous()
        );
    }

    private InvestigationCommand.Start startCommand(
        StartInvestigationRequest request,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID actorId,
        Instant now
    ) {
        return new InvestigationCommand.Start(
            request.runId(),
            organizationId,
            projectId,
            incidentId,
            actorId,
            new InvestigationCommand.Budget(
                request.maxRounds(),
                request.maxToolCalls(),
                request.maxEvidenceItems(),
                request.maxTokens()
            ),
            now,
            request.deadlineAt()
        );
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
