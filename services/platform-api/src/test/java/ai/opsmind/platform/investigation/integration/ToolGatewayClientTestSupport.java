package ai.opsmind.platform.investigation.integration;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class ToolGatewayClientTestSupport {

    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    static final UUID ACTOR_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    static final UUID INTENT_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
    static final UUID AUDIT_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private ToolGatewayClientTestSupport() { }

    static ObjectMapper mapper() {
        return JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    static InvestigationToolIntentCatalog catalog(ObjectMapper mapper) {
        return InvestigationToolIntentCatalogResourceLoader.load(mapper);
    }

    static AnalysisRuntimeResponse.ToolIntent intent(InvestigationToolIntentCatalog catalog) {
        InvestigationToolIntentCatalog.Selector selector = catalog.publicSelectors().getFirst();
        return new AnalysisRuntimeResponse.ToolIntent(
            INTENT_ID, selector.connector(), selector.operation(), selector.argumentsDigest(),
            "Collect the approved latency signal."
        );
    }

    static InvestigationToolGatewayClient.ToolExecutionContext context() {
        return new InvestigationToolGatewayClient.ToolExecutionContext(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID, ACTOR_ID, NOW.plusSeconds(60)
        );
    }

    static ToolGatewayClientProperties properties(URI endpoint, int responseLimit) {
        return new ToolGatewayClientProperties(
            true, endpoint, true, Duration.ofSeconds(1), Duration.ofSeconds(10), responseLimit
        );
    }

    static Map<String, Object> successResponse(
        ObjectMapper mapper,
        byte[] requestBody,
        boolean duplicate
    ) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = mapper.readValue(requestBody, Map.class);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("metric", "http_request_duration_seconds");
        content.put("points", List.of(
            Map.of("timestamp", "2030-01-01T00:00:00Z", "value", 1.2),
            Map.of("timestamp", "2030-01-01T00:01:00Z", "value", 1.6)
        ));
        String contentDigest = new EvidenceContentCanonicalizer(mapper)
            .canonicalize(content).digest().substring("sha256:".length());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "fixture-prometheus");
        evidence.put("target_identity", "prometheus:synthetic/opsmind-api");
        evidence.put("observed_at", "2030-01-01T00:03:00Z");
        evidence.put("window_start", "2030-01-01T00:00:00Z");
        evidence.put("window_end", "2030-01-01T00:03:00Z");
        evidence.put("connector_version", "fixture-observability@1");
        evidence.put("manifest_version", "observability.metrics.query@1");
        evidence.put("trust_class", "synthetic");
        evidence.put("content_digest", contentDigest);
        evidence.put("content", content);
        evidence.put("redacted_fields", 0);
        evidence.put("truncated", false);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("execution_id", request.get("execution_id"));
        response.put("status", duplicate ? "DUPLICATE" : "SUCCEEDED");
        response.put("evidence", new ArrayList<>(List.of(evidence)));
        response.put("audit_event_id", AUDIT_ID.toString());
        response.put("request_digest", java.util.HexFormat.of().formatHex(
            RequestDigest.sha256(requestBody)
        ));
        response.put("manifest_version", "observability.metrics.query@1");
        response.put("source_provenance", "fixture-prometheus/fixture-observability@1");
        response.put("redaction_count", 0);
        response.put("truncated", false);
        response.put("duplicate", duplicate);
        return response;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> evidence(Map<String, Object> response) {
        return (Map<String, Object>) ((List<?>) response.get("evidence")).getFirst();
    }
}
