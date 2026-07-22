package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AnalysisRequestCanonicalizerTest {

    private static final UUID INCIDENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVIDENCE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void matchesPythonGoldenCanonicalBytesAndDigest() throws Exception {
        AnalysisRequestCanonicalizer canonicalizer = new AnalysisRequestCanonicalizer(
            new ObjectMapper()
        );
        StartIncidentAnalysisRequest request = new StartIncidentAnalysisRequest(
            RUN_ID,
            "investigate",
            "incident_investigation",
            1_000,
            2,
            Instant.parse("2099-01-01T00:00:00Z")
        );
        ResolvedAnalysisEvidence evidence = new ResolvedAnalysisEvidence(
            "Investigate the synthetic redacted latency signal.",
            "prompt-incident-investigation-v1",
            List.of(new AnalysisEvidenceReference(
                EVIDENCE_ID,
                "sha256:" + "a".repeat(64),
                "metric"
            )),
            List.of("redacted_metrics")
        );

        PreparedAnalysisRequest prepared = canonicalizer.prepare(
            TENANT_ID, INCIDENT_ID, request, evidence
        );

        String expectedBody = "{\"analysis_mode\":\"investigate\",\"context_refs\":[{"
            + "\"digest\":\"sha256:" + "a".repeat(64) + "\","
            + "\"evidence_id\":\"" + EVIDENCE_ID + "\",\"source_type\":\"metric\"}],"
            + "\"data_classifications\":[\"redacted_metrics\"],"
            + "\"deadline_at\":\"2099-01-01T00:00:00Z\","
            + "\"incident_id\":\"" + INCIDENT_ID + "\","
            + "\"prompt\":\"Investigate the synthetic redacted latency signal.\","
            + "\"prompt_version\":\"prompt-incident-investigation-v1\","
            + "\"purpose\":\"incident_investigation\",\"run_id\":\"" + RUN_ID + "\","
            + "\"schema_version\":\"analysis-v1\",\"tenant_id\":\"" + TENANT_ID + "\","
            + "\"token_budget\":1000,\"tool_budget\":2}";
        assertThat(new String(prepared.body(), StandardCharsets.UTF_8)).isEqualTo(expectedBody);
        assertThat(prepared.requestDigest()).isEqualTo(goldenDigest());
    }

    private String goldenDigest() throws Exception {
        Path current = Path.of("").toAbsolutePath();
        Path root = Files.isDirectory(current.resolve("packages"))
            ? current
            : current.resolve("../..").normalize();
        return Files.readString(
            root.resolve("packages/contracts/fixtures/deepseek/analysis-request-v1.digest"),
            StandardCharsets.UTF_8
        ).trim();
    }
}
