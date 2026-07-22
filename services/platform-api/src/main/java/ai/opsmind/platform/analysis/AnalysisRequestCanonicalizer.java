package ai.opsmind.platform.analysis;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.RequestDigest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class AnalysisRequestCanonicalizer {

    private final ObjectMapper objectMapper;

    public AnalysisRequestCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PreparedAnalysisRequest prepare(
        UUID organizationId,
        UUID incidentId,
        StartIncidentAnalysisRequest request,
        ResolvedAnalysisEvidence evidence
    ) {
        Instant deadline = normalizedDeadline(request.deadlineAt());
        List<String> classifications = evidence.dataClassifications().stream().sorted().toList();
        TreeMap<String, Object> payload = new TreeMap<>();
        payload.put("analysis_mode", request.analysisMode());
        payload.put("context_refs", evidence.contextRefs().stream().map(this::context).toList());
        payload.put("data_classifications", classifications);
        payload.put("deadline_at", deadline.toString());
        payload.put("incident_id", incidentId.toString());
        payload.put("prompt", evidence.prompt());
        payload.put("prompt_version", evidence.promptVersion());
        payload.put("purpose", request.purpose());
        payload.put("run_id", request.runId().toString());
        payload.put("schema_version", "analysis-v1");
        payload.put("tenant_id", organizationId.toString());
        payload.put("token_budget", request.tokenBudget());
        payload.put("tool_budget", request.toolBudget());
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            String digest = "sha256:" + HexFormat.of().formatHex(RequestDigest.sha256(body));
            return new PreparedAnalysisRequest(
                body,
                digest,
                organizationId,
                incidentId,
                request.runId(),
                evidence.promptVersion(),
                request.purpose(),
                Set.copyOf(classifications),
                deadline
            );
        }
        catch (JacksonException exception) {
            throw new PlatformProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "analysis.serialization-failed",
                "The analysis request could not be serialized safely.",
                exception
            );
        }
    }

    public Instant normalizedDeadline(Instant deadline) {
        return deadline.truncatedTo(ChronoUnit.SECONDS);
    }

    private TreeMap<String, Object> context(AnalysisEvidenceReference reference) {
        TreeMap<String, Object> value = new TreeMap<>();
        value.put("digest", reference.digest());
        value.put("evidence_id", reference.evidenceId().toString());
        value.put("source_type", reference.sourceType());
        return value;
    }

    public String canonicalBody(PreparedAnalysisRequest request) {
        return new String(request.body(), StandardCharsets.UTF_8);
    }
}
