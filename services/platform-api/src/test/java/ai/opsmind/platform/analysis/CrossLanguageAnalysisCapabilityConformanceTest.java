package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE5_CROSS_LANGUAGE", matches = "true")
class CrossLanguageAnalysisCapabilityConformanceTest {

    private static final String ISSUER = "https://platform.example.test";
    private static final String AUDIENCE = "opsmind-ai-runtime";
    private static final String KEY_ID = "analysis-conformance-key";

    @TempDir
    Path temporaryDirectory;

    @Test
    void javaIssuedCapabilityAndCanonicalBodyAreAcceptedByPython() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        Instant deadline = Instant.now().plusSeconds(60);
        StartIncidentAnalysisRequest request = new StartIncidentAnalysisRequest(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "investigate",
            "incident_investigation",
            1_000,
            0,
            deadline
        );
        ResolvedAnalysisEvidence evidence = new ResolvedAnalysisEvidence(
            "Synthetic redacted latency signal.",
            "prompt-incident-v1",
            List.of(),
            List.of("redacted_metrics")
        );
        UUID tenantId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID incidentId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        ObjectMapper mapper = new ObjectMapper();
        PreparedAnalysisRequest prepared = new AnalysisRequestCanonicalizer(mapper)
            .prepare(tenantId, incidentId, request, evidence);
        String token = new RsaAnalysisCapabilityTokenIssuer(
            keyPair.getPrivate(),
            KEY_ID,
            URI.create(ISSUER),
            AUDIENCE,
            Duration.ofMinutes(4),
            Clock.systemUTC(),
            new SecureRandom(),
            mapper
        ).issue(new AnalysisCapabilityGrant(
            "55555555-5555-4555-8555-555555555555",
            tenantId,
            incidentId,
            request.runId(),
            request.purpose(),
            prepared.dataClassifications(),
            prepared.requestDigest(),
            prepared.deadlineAt()
        ));

        Path jwks = temporaryDirectory.resolve("capability-jwks.json");
        Path body = temporaryDirectory.resolve("analysis-request.json");
        Path capability = temporaryDirectory.resolve("capability.jwt");
        Files.writeString(jwks, mapper.writeValueAsString(jwks((RSAPublicKey) keyPair.getPublic())));
        Files.write(body, prepared.body());
        Files.writeString(capability, token, StandardCharsets.US_ASCII);

        Process process = new ProcessBuilder(
            requiredEnvironment("OPSMIND_PHASE5_PYTHON"),
            "-c",
            pythonVerifier(),
            repositoryRoot().toString(),
            jwks.toString(),
            body.toString(),
            capability.toString()
        ).redirectErrorStream(true).start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        byte[] output = process.getInputStream().readNBytes(8_193);

        assertThat(completed).as("Python verifier completed").isTrue();
        assertThat(output.length).as("bounded verifier output").isLessThanOrEqualTo(8_192);
        assertThat(process.exitValue())
            .withFailMessage("Python verifier failed: %s", new String(output, StandardCharsets.UTF_8))
            .isZero();
        assertThat(new String(output, StandardCharsets.UTF_8).trim())
            .isEqualTo("CrossLanguageCapability=PASS");
    }

    private Map<String, Object> jwks(RSAPublicKey key) {
        return Map.of("keys", List.of(Map.of(
            "kty", "RSA",
            "kid", KEY_ID,
            "use", "sig",
            "alg", "RS256",
            "n", encodedUnsigned(key.getModulus()),
            "e", encodedUnsigned(key.getPublicExponent())
        )));
    }

    private String encodedUnsigned(BigInteger value) {
        byte[] signed = value.toByteArray();
        byte[] unsigned = signed.length > 1 && signed[0] == 0
            ? java.util.Arrays.copyOfRange(signed, 1, signed.length)
            : signed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
    }

    private String pythonVerifier() {
        return """
            import sys
            from pathlib import Path
            root, jwks, body, token = map(Path, sys.argv[1:])
            sys.path.insert(0, str(root / 'services' / 'ai-runtime' / 'src'))
            from opsmind_ai_runtime.application.delegated_capability import analysis_request_digest
            from opsmind_ai_runtime.application.rsa_jwks_capability import RsaJwksCapabilityVerifier
            from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1
            request = AnalysisRequestV1.model_validate_json(body.read_text(encoding='utf-8'))
            verifier = RsaJwksCapabilityVerifier.from_file(
                str(jwks),
                expected_issuer='https://platform.example.test',
                expected_audience='opsmind-ai-runtime',
            )
            claims = verifier.verify(token.read_text(encoding='ascii'))
            if claims is None:
                print('CrossLanguageCapability=VERIFY_FAILED')
                raise SystemExit(2)
            if claims.request_digest != analysis_request_digest(request):
                print('CrossLanguageCapability=DIGEST_FAILED')
                raise SystemExit(3)
            if claims.tenant_id != request.tenant_id or claims.run_id != request.run_id:
                print('CrossLanguageCapability=SCOPE_FAILED')
                raise SystemExit(4)
            print('CrossLanguageCapability=PASS')
            """;
    }

    private Path repositoryRoot() {
        String configured = requiredEnvironment("OPSMIND_REPOSITORY_ROOT");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("services/ai-runtime"))) {
            throw new IllegalStateException("OPSMIND_REPOSITORY_ROOT is invalid.");
        }
        return root;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for cross-language conformance.");
        }
        return value;
    }
}
