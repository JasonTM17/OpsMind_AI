package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import tools.jackson.databind.ObjectMapper;

class DelegatedCapabilityRequestBindingTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final RequestDigester digester = new RequestDigester(new ObjectMapper());
    private final DelegatedCapabilityRequestBinding binding =
        new DelegatedCapabilityRequestBinding(digester);

    @Test
    void acceptsOnlyTheCanonicalRequestDigest() {
        ToolExecutionRequest request = request(Map.of("service", "opsmind-api"));
        binding.validate(capability(), jwt(digester.digest(request)), request);
    }

    @Test
    void rejectsArgumentOrDigestDrift() {
        ToolExecutionRequest authorized = request(Map.of("service", "opsmind-api"));
        ToolExecutionRequest drifted = new ToolExecutionRequest(
            authorized.executionId(), TENANT, PROJECT, INCIDENT, RUN, "operator-001",
            authorized.tool(), authorized.action(), authorized.schemaVersion(), authorized.resource(),
            Map.of("service", "other-service"), authorized.deadlineAt(), authorized.resultBudget()
        );

        assertMismatch(() -> binding.validate(capability(), jwt(digester.digest(authorized)), drifted));
        assertMismatch(() -> binding.validate(capability(), jwt("0".repeat(64)), authorized));
    }

    @Test
    void rejectsTenantActionResourceAndBudgetScopeDriftEvenWithMatchingBodyDigest() {
        ToolExecutionRequest authorized = request(Map.of("service", "opsmind-api"));
        List<ToolExecutionRequest> forged = List.of(
            copy(authorized, UUID.randomUUID(), authorized.action(), authorized.resource(), 4_096),
            copy(authorized, PROJECT, "metrics.write", authorized.resource(), 4_096),
            copy(authorized, PROJECT, authorized.action(), "prometheus:synthetic/other", 4_096),
            copy(authorized, PROJECT, authorized.action(), authorized.resource(), 65_537)
        );
        forged.forEach(request -> assertMismatch(() ->
            binding.validate(capability(), jwt(digester.digest(request)), request)));
    }

    private ToolExecutionRequest request(Map<String, Object> arguments) {
        return new ToolExecutionRequest(
            UUID.fromString("55555555-5555-4555-8555-555555555555"), TENANT, PROJECT,
            INCIDENT, RUN, "operator-001", "observability", "metrics.query", "1.0",
            "prometheus:synthetic/opsmind-api", arguments, NOW.plusSeconds(30),
            new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }

    private VerifiedCapability capability() {
        return new VerifiedCapability(
            "capability-001", "operator-001", TENANT, PROJECT, INCIDENT, RUN,
            Set.of("observability:metrics.query:1.0"),
            Set.of("prometheus:synthetic/opsmind-api"), Set.of("operator:read"),
            1, 65_536, "policy-test", NOW.plusSeconds(120)
        );
    }

    private ToolExecutionRequest copy(
        ToolExecutionRequest source,
        UUID projectId,
        String action,
        String resource,
        int maximumBytes
    ) {
        return new ToolExecutionRequest(
            source.executionId(), TENANT, projectId, INCIDENT, RUN, source.actorSubject(),
            source.tool(), action, source.schemaVersion(), resource, source.arguments(),
            source.deadlineAt(), new ToolExecutionRequest.ResultBudget(maximumBytes, 10)
        );
    }

    private Jwt jwt(String digest) {
        return Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://platform.invalid.example")
            .subject("operator-001")
            .audience(List.of("opsmind-tool-gateway"))
            .issuedAt(NOW.minusSeconds(1))
            .expiresAt(NOW.plusSeconds(120))
            .claim("request_digest", digest)
            .build();
    }

    private void assertMismatch(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.CAPABILITY_SCOPE_MISMATCH));
    }
}
