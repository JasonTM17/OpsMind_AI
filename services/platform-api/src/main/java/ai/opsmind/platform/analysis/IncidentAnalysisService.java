package ai.opsmind.platform.analysis;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    prefix = "opsmind.ai-runtime.client",
    name = "enabled",
    havingValue = "true"
)
final class IncidentAnalysisService {

    private final IncidentAnalysisAuthorizer authorizer;
    private final AnalysisEvidenceResolver evidenceResolver;
    private final AnalysisRequestCanonicalizer canonicalizer;
    private final AnalysisCapabilityTokenIssuer tokenIssuer;
    private final AnalysisRuntimeClient runtimeClient;

    IncidentAnalysisService(
        IncidentAnalysisAuthorizer authorizer,
        AnalysisEvidenceResolver evidenceResolver,
        AnalysisRequestCanonicalizer canonicalizer,
        AnalysisCapabilityTokenIssuer tokenIssuer,
        AnalysisRuntimeClient runtimeClient
    ) {
        this.authorizer = authorizer;
        this.evidenceResolver = evidenceResolver;
        this.canonicalizer = canonicalizer;
        this.tokenIssuer = tokenIssuer;
        this.runtimeClient = runtimeClient;
    }

    AnalysisRuntimeResponse analyze(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        StartIncidentAnalysisRequest request,
        String correlationId
    ) {
        Instant normalizedDeadline = canonicalizer.normalizedDeadline(request.deadlineAt());
        if (!normalizedDeadline.isAfter(Instant.now())) {
            throw new PlatformProblemException(
                HttpStatus.REQUEST_TIMEOUT,
                "analysis.deadline-exceeded",
                "The analysis deadline has elapsed."
            );
        }
        AuthorizedIncidentAnalysisEvidence authorizedEvidence = authorizer.requireEvidence(
            principal,
            organizationId,
            projectId,
            incidentId
        );
        ResolvedAnalysisEvidence evidence = evidenceResolver.resolve(
            authorizedEvidence,
            request.analysisMode(),
            request.purpose()
        );
        PreparedAnalysisRequest prepared = canonicalizer.prepare(
            organizationId,
            incidentId,
            request,
            evidence
        );
        if (!prepared.deadlineAt().isAfter(Instant.now())) {
            throw new PlatformProblemException(
                HttpStatus.REQUEST_TIMEOUT,
                "analysis.deadline-exceeded",
                "The analysis deadline elapsed while evidence was being resolved."
            );
        }
        String capability;
        try {
            capability = tokenIssuer.issue(new AnalysisCapabilityGrant(
                authorizedEvidence.actorId().toString(),
                organizationId,
                incidentId,
                request.runId(),
                request.purpose(),
                prepared.dataClassifications(),
                prepared.requestDigest(),
                prepared.deadlineAt()
            ));
        }
        catch (IllegalArgumentException exception) {
            throw new PlatformProblemException(
                HttpStatus.BAD_REQUEST,
                "analysis.deadline-invalid",
                "The analysis deadline exceeds the allowed capability lifetime.",
                exception
            );
        }
        return runtimeClient.analyze(prepared, capability, correlationId);
    }
}
