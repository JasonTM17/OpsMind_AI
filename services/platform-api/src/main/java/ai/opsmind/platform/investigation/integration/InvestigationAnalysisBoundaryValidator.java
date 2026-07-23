package ai.opsmind.platform.investigation.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisEvidenceReference;
import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.analysis.ResolvedAnalysisEvidence;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;
import ai.opsmind.platform.evidence.ResolvedEvidenceRecord;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;

import org.springframework.http.HttpStatus;

/** Fail-closed validation shared by evidence ingress and model-response egress. */
final class InvestigationAnalysisBoundaryValidator {

    private final InvestigationToolIntentCatalog catalog;
    private final EvidenceContentCanonicalizer evidenceCanonicalizer;

    InvestigationAnalysisBoundaryValidator(
        InvestigationToolIntentCatalog catalog,
        EvidenceContentCanonicalizer evidenceCanonicalizer
    ) {
        this.catalog = catalog;
        this.evidenceCanonicalizer = evidenceCanonicalizer;
    }

    List<ResolvedEvidenceRecord> requireEvidence(
        InvestigationAnalysisRequest request,
        List<UUID> evidenceIds,
        List<ResolvedEvidenceRecord> records
    ) {
        if (records.size() != evidenceIds.size()) throw invalidEvidence();
        for (int index = 0; index < records.size(); index++) {
            ResolvedEvidenceRecord record = records.get(index);
            if (record == null || !evidenceIds.get(index).equals(record.evidenceId())
                || request.initialIncident().incidentId().equals(record.evidenceId())
                || !request.runId().equals(record.runId()) || !"metric".equals(record.sourceType())
                || record.observedAt() == null || !bounded(record.source(), 256)
                || !bounded(record.targetIdentity(), 256) || !bounded(record.trustClass(), 64)
                || record.truncated()) {
                throw invalidEvidence();
            }
            try {
                evidenceCanonicalizer.verify(record.canonicalContent(), record.digest());
            }
            catch (IllegalArgumentException exception) {
                throw invalidEvidence();
            }
        }
        return List.copyOf(records);
    }

    void requireSameAuthorization(
        AuthorizedIncidentAnalysisEvidence initial,
        AuthorizedIncidentAnalysisEvidence current
    ) {
        if (current == null || !initial.organizationId().equals(current.organizationId())
            || !initial.projectId().equals(current.projectId())
            || !initial.incidentId().equals(current.incidentId())
            || !initial.actorId().equals(current.actorId())) {
            throw new PlatformProblemException(
                HttpStatus.NOT_FOUND, "investigation.context-not-found",
                "The investigation context was not found or is not visible."
            );
        }
    }

    void requireIncidentEvidence(
        AuthorizedIncidentAnalysisEvidence initial,
        ResolvedAnalysisEvidence evidence
    ) {
        if (evidence.contextRefs().size() != 1
            || !evidence.dataClassifications().equals(List.of("redacted_incident_summary"))) {
            throw invalidEvidence();
        }
        AnalysisEvidenceReference reference = evidence.contextRefs().get(0);
        if (!initial.incidentId().equals(reference.evidenceId())
            || !"incident_summary".equals(reference.sourceType())) {
            throw invalidEvidence();
        }
    }

    void validateResponse(
        InvestigationAnalysisRequest request,
        ResolvedAnalysisEvidence evidence,
        AnalysisRuntimeResponse response
    ) {
        if (response == null || !request.runId().equals(response.runId())
            || !evidence.promptVersion().equals(response.promptVersion())
            || response.usage().totalTokens() > request.remainingTokens()
            || response.requestedToolCalls().size() > request.remainingToolCalls()) {
            throw invalidResponse();
        }
        try {
            response.requestedToolCalls().forEach(catalog::resolve);
        }
        catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
        Map<UUID, String> allowedCitations = new HashMap<>();
        evidence.contextRefs().stream()
            .filter(reference -> "metric".equals(reference.sourceType()))
            .forEach(reference -> allowedCitations.put(reference.evidenceId(), reference.digest()));
        List<AnalysisRuntimeResponse.Citation> citations = new ArrayList<>(response.citations());
        response.hypotheses().forEach(hypothesis -> citations.addAll(hypothesis.citations()));
        for (AnalysisRuntimeResponse.Citation citation : citations) {
            String digest = allowedCitations.get(citation.evidenceId());
            if (digest == null || !sameDigest(digest, citation.digest())) throw invalidResponse();
        }
    }

    private boolean sameDigest(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank()
            && value.codePointCount(0, value.length()) <= maximum;
    }

    private PlatformProblemException invalidEvidence() {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE, "investigation.evidence-invalid",
            "Authorized investigation evidence failed validation."
        );
    }

    private PlatformProblemException invalidResponse() {
        return new PlatformProblemException(
            HttpStatus.BAD_GATEWAY, "investigation.ai-response-invalid",
            "The investigation model response failed validation."
        );
    }
}
