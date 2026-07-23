package ai.opsmind.platform.investigation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.evidence.EvidenceIdentity;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ToolGatewayRequestCanonicalizerTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ACTOR_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID INTENT_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");

    @Test
    void resolvesImmutableTemplateAndBindsCanonicalBytesToDeterministicIdentity() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InvestigationToolIntentCatalog catalog = InvestigationToolIntentCatalogResourceLoader.load(mapper);
        InvestigationToolIntentCatalog.Selector selector = catalog.publicSelectors().getFirst();
        AnalysisRuntimeResponse.ToolIntent intent = new AnalysisRuntimeResponse.ToolIntent(
            INTENT_ID, selector.connector(), selector.operation(), selector.argumentsDigest(), "read"
        );
        ToolGatewayRequestCanonicalizer canonicalizer = new ToolGatewayRequestCanonicalizer(
            catalog, mapper, Duration.ofMinutes(2), Clock.fixed(NOW, ZoneOffset.UTC)
        );

        PreparedToolGatewayRequest prepared = canonicalizer.prepare(intent, context(NOW.plusSeconds(90)));

        Map<String, Object> body = mapper.readValue(
            prepared.body(), new TypeReference<Map<String, Object>>() { }
        );
        assertThat(body.keySet()).containsExactlyInAnyOrder(
            "action", "actor_subject", "arguments", "deadline_at", "execution_id",
            "incident_id", "project_id", "resource", "result_budget", "run_id",
            "schema_version", "tenant_id", "tool"
        );
        assertThat(body.get("arguments")).isEqualTo(Map.of(
            "service", "opsmind-api",
            "metric", "http_request_duration_seconds",
            "max_points", 3
        ));
        assertThat(prepared.executionId()).isEqualTo(
            EvidenceIdentity.executionId(ORGANIZATION_ID, RUN_ID, INTENT_ID)
        );
        assertThat(prepared.evidenceId()).isEqualTo(
            EvidenceIdentity.evidenceId(ORGANIZATION_ID, RUN_ID, INTENT_ID)
        );
        assertThat(prepared.deadlineAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(prepared.requestDigest()).isEqualTo(
            HexFormat.of().formatHex(RequestDigest.sha256(prepared.body()))
        );
        assertThat(prepared.body()).isEqualTo(mapper.writeValueAsBytes(new TreeMap<>(body)));
        assertThat(prepared.body()).isEqualTo(Files.readString(
            repositoryRoot().resolve(
                "packages/contracts/fixtures/tool-gateway/"
                    + "investigation-tool-execution-request-v1.canonical.json"
            )
        ).stripTrailing().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnknownSelectorAndElapsedDeadlineBeforeAnyTransportWork() {
        ObjectMapper mapper = new ObjectMapper();
        InvestigationToolIntentCatalog catalog = InvestigationToolIntentCatalogResourceLoader.load(mapper);
        ToolGatewayRequestCanonicalizer canonicalizer = new ToolGatewayRequestCanonicalizer(
            catalog, mapper, Duration.ofMinutes(2), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        AnalysisRuntimeResponse.ToolIntent unknown = new AnalysisRuntimeResponse.ToolIntent(
            INTENT_ID, "metrics", "query", "sha256:" + "0".repeat(64), "read"
        );
        InvestigationToolIntentCatalog.Selector selector = catalog.publicSelectors().getFirst();
        AnalysisRuntimeResponse.ToolIntent valid = new AnalysisRuntimeResponse.ToolIntent(
            INTENT_ID, selector.connector(), selector.operation(), selector.argumentsDigest(), "read"
        );

        assertThatThrownBy(() -> canonicalizer.prepare(unknown, context(NOW.plusSeconds(30))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowlisted");
        assertThatThrownBy(() -> canonicalizer.prepare(valid, context(NOW)))
            .isInstanceOf(ai.opsmind.platform.common.api.PlatformProblemException.class);
    }

    private InvestigationToolGatewayClient.ToolExecutionContext context(Instant deadline) {
        return new InvestigationToolGatewayClient.ToolExecutionContext(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID, ACTOR_ID, deadline
        );
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("package.json"))
                && Files.isDirectory(candidate.resolve("packages/contracts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root cannot be located.");
    }
}
