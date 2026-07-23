package ai.opsmind.platform.investigation.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.CollectedEvidence;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;

import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class ToolGatewayResponseValidator {

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final EvidenceContentCanonicalizer evidenceCanonicalizer;
    private final ObjectReader reader;

    ToolGatewayResponseValidator(
        EvidenceContentCanonicalizer evidenceCanonicalizer,
        ObjectMapper objectMapper
    ) {
        this.evidenceCanonicalizer = evidenceCanonicalizer;
        this.reader = objectMapper.readerFor(ToolGatewayExecutionResponse.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    InvestigationToolGatewayClient.ToolEvidence validate(
        byte[] body,
        PreparedToolGatewayRequest request
    ) {
        try {
            ToolGatewayExecutionResponse response = reader.readValue(body);
            validateEnvelope(response, request);
            ToolGatewayExecutionResponse.EvidenceEnvelope evidence = response.evidence().getFirst();
            EvidenceContentCanonicalizer.CanonicalEvidenceContent canonical =
                evidenceCanonicalizer.canonicalize(evidence.content());
            validateEvidence(response, evidence, canonical, request.invocation());
            CollectedEvidence collected = new CollectedEvidence(
                request.executionId(), response.auditEventId(), request.requestDigest(),
                sourceType(request.invocation()), evidence.source(), evidence.targetIdentity(),
                evidence.observedAt(), evidence.windowStart(), evidence.windowEnd(),
                evidence.connectorVersion(), evidence.manifestVersion(),
                request.invocation().policyVersion(), response.sourceProvenance(),
                evidence.trustClass(), canonical.digest(), canonical.json(),
                evidence.redactedFields(), false, null, response.duplicate()
            );
            return new InvestigationToolGatewayClient.ToolEvidence(
                request.intentId(), request.evidenceId(), canonical.digest(),
                sourceType(request.invocation()), collected
            );
        }
        catch (JacksonException | IllegalArgumentException exception) {
            throw invalidResponse(exception);
        }
    }

    ToolGatewayExecutionResponse parseDecision(byte[] body) {
        try {
            return reader.readValue(body);
        }
        catch (JacksonException | IllegalArgumentException exception) {
            throw invalidResponse(exception);
        }
    }

    private void validateEnvelope(
        ToolGatewayExecutionResponse response,
        PreparedToolGatewayRequest request
    ) {
        boolean succeeded = "SUCCEEDED".equals(response.status())
            && Boolean.FALSE.equals(response.duplicate());
        boolean duplicate = "DUPLICATE".equals(response.status())
            && Boolean.TRUE.equals(response.duplicate());
        if ((!succeeded && !duplicate) || response.denialCode() != null
            || !request.executionId().equals(response.executionId())
            || !same(request.requestDigest(), response.requestDigest())
            || response.auditEventId() == null || response.evidence() == null
            || response.evidence().size() != 1 || response.redactionCount() == null
            || response.redactionCount() < 0 || !Boolean.FALSE.equals(response.truncated())
            || !request.invocation().expectedManifestVersion().equals(response.manifestVersion())) {
            throw invalidResponse(null);
        }
    }

    private void validateEvidence(
        ToolGatewayExecutionResponse response,
        ToolGatewayExecutionResponse.EvidenceEnvelope evidence,
        EvidenceContentCanonicalizer.CanonicalEvidenceContent canonical,
        InvestigationToolInvocation invocation
    ) {
        String rawDigest = canonical.digest().substring("sha256:".length());
        String provenance = evidence.source() + "/" + evidence.connectorVersion();
        if (!same(rawDigest, evidence.contentDigest())
            || !invocation.resource().equals(evidence.targetIdentity())
            || !invocation.expectedManifestVersion().equals(evidence.manifestVersion())
            || !provenance.equals(response.sourceProvenance())
            || evidence.observedAt() == null || evidence.windowStart() == null
            || evidence.windowEnd() == null || evidence.observedAt().isBefore(evidence.windowStart())
            || evidence.observedAt().isAfter(evidence.windowEnd())
            || evidence.redactedFields() == null || evidence.redactedFields() < 0
            || !evidence.redactedFields().equals(response.redactionCount())
            || !Boolean.FALSE.equals(evidence.truncated()) || evidence.artifactReference() != null
            || canonical.byteCount() > invocation.maximumBytes()
            || !listsWithin(evidence.content(), invocation.maximumItems())) {
            throw invalidResponse(null);
        }
    }

    private boolean listsWithin(Object value, int maximumItems) {
        if (value instanceof List<?> list) {
            return list.size() <= maximumItems
                && list.stream().allMatch(item -> listsWithin(item, maximumItems));
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().allMatch(item -> listsWithin(item, maximumItems));
        }
        return true;
    }

    private String sourceType(InvestigationToolInvocation invocation) {
        if ("metrics".equals(invocation.connector())) return "metric";
        throw invalidResponse(null);
    }

    private boolean same(String expected, String actual) {
        return expected != null && actual != null
            && DIGEST.matcher(expected).matches() && DIGEST.matcher(actual).matches()
            && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    PlatformProblemException invalidResponse(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.BAD_GATEWAY,
            "dependency.tool-gateway-invalid-response",
            "The Tool Gateway returned an invalid response.",
            cause
        );
    }
}
