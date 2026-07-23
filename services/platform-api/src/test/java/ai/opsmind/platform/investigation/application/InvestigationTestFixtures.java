package ai.opsmind.platform.investigation.application;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.integration.InvestigationAnalysisRequest;

final class InvestigationTestFixtures {

    private InvestigationTestFixtures() { }

    static InvestigationExecutionContext context(InvestigationCommand.Start command) {
        return new InvestigationExecutionContext(
            new OpsMindPrincipal(
                URI.create("https://idp.example.test/opsmind"),
                "test-operator",
                null,
                null,
                Set.of("incident:analyze")
            ),
            incident(command)
        );
    }

    static InvestigationAnalysisRequest analysisRequest(
        InvestigationCommand.Start command,
        Set<UUID> evidenceIds,
        int completedRounds
    ) {
        return new InvestigationAnalysisRequest(
            context(command).principal(), incident(command), command.runId(), evidenceIds,
            completedRounds, command.budget().maxRounds() - completedRounds,
            command.budget().maxTokens(), command.budget().maxToolCalls(), command.deadlineAt()
        );
    }

    private static AuthorizedIncidentAnalysisEvidence incident(InvestigationCommand.Start command) {
        return new AuthorizedIncidentAnalysisEvidence(
            command.organizationId(), command.projectId(), command.incidentId(), command.actorId(),
            "Synthetic incident", "Synthetic evidence boundary", IncidentSeverity.SEV2,
            IncidentStatus.INVESTIGATING, null, null, 0
        );
    }
}
