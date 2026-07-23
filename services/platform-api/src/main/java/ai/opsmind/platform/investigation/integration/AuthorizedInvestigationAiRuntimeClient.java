package ai.opsmind.platform.investigation.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisCapabilityGrant;
import ai.opsmind.platform.analysis.AnalysisCapabilityTokenIssuer;
import ai.opsmind.platform.analysis.AnalysisEvidenceResolver;
import ai.opsmind.platform.analysis.AnalysisRequestCanonicalizer;
import ai.opsmind.platform.analysis.AnalysisRuntimeClient;
import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.analysis.PreparedAnalysisRequest;
import ai.opsmind.platform.analysis.ResolvedAnalysisEvidence;
import ai.opsmind.platform.analysis.StartIncidentAnalysisRequest;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;
import ai.opsmind.platform.evidence.ResolvedEvidenceRecord;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisContext;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.springframework.http.HttpStatus;

import tools.jackson.databind.ObjectMapper;

/** Re-authorizes evidence, signs the exact request, then delegates to the existing AI transport. */
public final class AuthorizedInvestigationAiRuntimeClient implements InvestigationAiRuntimeClient {

    private final IncidentAnalysisAuthorizer authorizer;
    private final AnalysisEvidenceResolver evidenceResolver;
    private final AnalysisRequestCanonicalizer canonicalizer;
    private final AnalysisCapabilityTokenIssuer tokenIssuer;
    private final AnalysisRuntimeClient runtimeClient;
    private final InvestigationToolIntentCatalog catalog;
    private final InvestigationAnalysisPromptAssembler promptAssembler;
    private final InvestigationAnalysisBoundaryValidator boundaryValidator;
    private final Duration maximumCapabilityLifetime;
    private final Clock clock;

    public AuthorizedInvestigationAiRuntimeClient(
        IncidentAnalysisAuthorizer authorizer,
        AnalysisEvidenceResolver evidenceResolver,
        AnalysisRequestCanonicalizer canonicalizer,
        AnalysisCapabilityTokenIssuer tokenIssuer,
        AnalysisRuntimeClient runtimeClient,
        InvestigationToolIntentCatalog catalog,
        EvidenceContentCanonicalizer evidenceCanonicalizer,
        ObjectMapper objectMapper,
        Duration maximumCapabilityLifetime,
        Clock clock
    ) {
        this.authorizer = authorizer;
        this.evidenceResolver = evidenceResolver;
        this.canonicalizer = canonicalizer;
        this.tokenIssuer = tokenIssuer;
        this.runtimeClient = runtimeClient;
        this.catalog = catalog;
        this.promptAssembler = new InvestigationAnalysisPromptAssembler(objectMapper);
        this.boundaryValidator = new InvestigationAnalysisBoundaryValidator(
            catalog, evidenceCanonicalizer
        );
        if (maximumCapabilityLifetime == null
            || maximumCapabilityLifetime.compareTo(Duration.ofSeconds(30)) < 0
            || maximumCapabilityLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Investigation capability lifetime is invalid.");
        }
        this.maximumCapabilityLifetime = maximumCapabilityLifetime;
        this.clock = clock;
    }

    @Override
    public AnalysisRuntimeResponse analyze(InvestigationAnalysisRequest request) {
        requireBudgetAndDeadline(request);
        List<UUID> evidenceIds = request.evidenceIds().stream().sorted().toList();
        AuthorizedIncidentAnalysisContext authorized = authorizer.requireEvidenceRecords(
            request.principal(), request.initialIncident().organizationId(),
            request.initialIncident().projectId(), request.initialIncident().incidentId(),
            request.runId(), evidenceIds
        );
        boundaryValidator.requireSameAuthorization(request.initialIncident(), authorized.incident());
        List<ResolvedEvidenceRecord> records = boundaryValidator.requireEvidence(
            request, evidenceIds, authorized.evidence()
        );
        ResolvedAnalysisEvidence incident = evidenceResolver.resolve(
            request.initialIncident(), "investigate", "incident_investigation"
        );
        boundaryValidator.requireIncidentEvidence(request.initialIncident(), incident);
        List<InvestigationToolIntentCatalog.Selector> selectors = request.remainingToolCalls() == 0
            ? List.of() : catalog.publicSelectors();
        ResolvedAnalysisEvidence evidence = promptAssembler.assemble(
            incident, records, selectors, request
        );
        StartIncidentAnalysisRequest analysisRequest = new StartIncidentAnalysisRequest(
            request.runId(), "investigate", "incident_investigation", request.remainingTokens(),
            request.remainingToolCalls(), roundDeadline(request.deadlineAt())
        );
        PreparedAnalysisRequest prepared = canonicalizer.prepare(
            request.initialIncident().organizationId(), request.initialIncident().incidentId(),
            analysisRequest, evidence
        );
        requireDeadline(prepared.deadlineAt());
        String capability = issueCapability(request, prepared);
        requireDeadline(prepared.deadlineAt());
        AnalysisRuntimeResponse response = runtimeClient.analyze(
            prepared, capability, correlationId(request)
        );
        boundaryValidator.validateResponse(request, evidence, response);
        return response;
    }

    private String issueCapability(
        InvestigationAnalysisRequest request,
        PreparedAnalysisRequest prepared
    ) {
        try {
            return tokenIssuer.issue(new AnalysisCapabilityGrant(
                request.initialIncident().actorId().toString(), prepared.tenantId(),
                prepared.incidentId(), prepared.runId(), prepared.purpose(),
                prepared.dataClassifications(), prepared.requestDigest(), prepared.deadlineAt()
            ));
        }
        catch (IllegalArgumentException exception) {
            throw new PlatformProblemException(
                HttpStatus.BAD_REQUEST, "investigation.deadline-invalid",
                "The investigation deadline exceeds capability policy.", exception
            );
        }
    }

    private void requireBudgetAndDeadline(InvestigationAnalysisRequest request) {
        if (request.remainingRounds() < 1 || request.remainingTokens() < 1) {
            throw new PlatformProblemException(
                HttpStatus.TOO_MANY_REQUESTS, "investigation.analysis-budget-exhausted",
                "The investigation analysis budget is exhausted."
            );
        }
        requireDeadline(canonicalizer.normalizedDeadline(request.deadlineAt()));
    }

    private void requireDeadline(Instant deadline) {
        if (!deadline.isAfter(Instant.now(clock))) {
            throw new PlatformProblemException(
                HttpStatus.REQUEST_TIMEOUT, "investigation.deadline-exceeded",
                "The investigation deadline has elapsed."
            );
        }
    }

    private Instant roundDeadline(Instant runDeadline) {
        Instant capabilityDeadline = canonicalizer.normalizedDeadline(
            Instant.now(clock).plus(maximumCapabilityLifetime)
        );
        Instant normalizedRunDeadline = canonicalizer.normalizedDeadline(runDeadline);
        return normalizedRunDeadline.isBefore(capabilityDeadline)
            ? normalizedRunDeadline : capabilityDeadline;
    }

    private String correlationId(InvestigationAnalysisRequest request) {
        return "investigation:" + request.runId() + ":round:" + (request.completedRounds() + 1);
    }

}
