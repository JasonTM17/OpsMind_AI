package ai.opsmind.platform.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class OAuthClientCredentialsWorkloadTokenProviderTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String CLIENT_SECRET = "secret-value";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OAuthTokenEndpointStub endpointStub;

    @BeforeEach
    void startServer() throws IOException {
        endpointStub = new OAuthTokenEndpointStub();
        endpointStub.respondJson(
            200, tokenResponse("Bearer", token(NOW.minusSeconds(1), NOW.plusSeconds(120)))
        );
    }

    @AfterEach
    void stopServer() {
        endpointStub.close();
    }

    @Test
    void obtainsOneBoundedBearerAndSingleFlightsConcurrentRefresh() throws Exception {
        WorkloadTokenProvider provider = provider(16_384, Duration.ofSeconds(2));
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = IntStream.range(0, 8)
                .mapToObj(ignored -> callers.submit(provider::accessToken)).toList();
            for (Future<String> result : results) assertThat(result.get()).isEqualTo(validToken());
        }
        finally {
            callers.shutdownNow();
        }
        assertThat(endpointStub.requestCount()).isEqualTo(1);
        assertThat(endpointStub.authorization()).startsWith("Basic ").doesNotContain(CLIENT_SECRET);
        assertThat(endpointStub.requestBody()).isEqualTo("grant_type=client_credentials&scope=tool.execute")
            .doesNotContain(CLIENT_SECRET);
        assertThat(provider.accessToken()).isEqualTo(validToken());
        assertThat(endpointStub.requestCount()).isEqualTo(1);
    }

    @Test
    void rejectsNonBearerMalformedAndExpiredTokens() {
        WorkloadTokenProvider provider = provider(16_384, Duration.ofSeconds(2));
        endpointStub.respondJson(200, tokenResponse("MAC", validToken()));
        assertInvalid(provider);

        endpointStub.respondJson(200, tokenResponse("Bearer", "not-a-jwt"));
        assertInvalid(provider);

        endpointStub.respondJson(200, tokenResponse(
            "Bearer", token(NOW.minusSeconds(120), NOW.minusSeconds(1))
        ));
        assertInvalid(provider);

        endpointStub.respondJson(200, "{\"access_token\":\"a\",\"access_token\":\"b\","
            + "\"token_type\":\"Bearer\",\"expires_in\":120}");
        assertInvalid(provider);

        endpointStub.respondJson(200, tokenResponse("Bearer", validToken())
            .replace("\"expires_in\":120", "\"expires_in\":\"120\""));
        assertInvalid(provider);
    }

    @Test
    void rejectsTokensFromAnotherIdentityDomain() {
        WorkloadTokenProvider provider = provider(16_384, Duration.ofSeconds(2));
        endpointStub.respondJson(200, tokenResponse("Bearer", token(
            endpointStub.issuer() + "/other",
            "opsmind-ai-runtime", "delegated_capability", NOW.minusSeconds(1), NOW.plusSeconds(120)
        )));
        assertInvalid(provider);
    }

    @Test
    void rejectsOversizedAndSanitizesEndpointFailures() {
        WorkloadTokenProvider provider = provider(1_024, Duration.ofSeconds(2));
        endpointStub.respondJson(200, "{\"padding\":\"" + "x".repeat(2_048) + "\"}");
        assertSanitized(provider);

        endpointStub.respondJson(500, "provider leaked " + CLIENT_SECRET);
        assertSanitized(provider);
    }

    @Test
    void cancelsAResponseThatExceedsTheRequestDeadline() {
        WorkloadTokenProvider provider = provider(16_384, Duration.ofMillis(100));
        endpointStub.respond(200, "application/json", tokenResponse("Bearer", validToken()), 400);
        assertSanitized(provider);
    }

    private WorkloadTokenProvider provider(int maximumBytes, Duration requestTimeout) {
        OAuthClientCredentialsProperties properties = new OAuthClientCredentialsProperties(
            true, endpointStub.issuer(), endpointStub.endpoint(), true,
            "opsmind-tool-gateway-workload",
            "platform-client", CLIENT_SECRET, "tool.execute", Duration.ofSeconds(1),
            requestTimeout, Duration.ofSeconds(30), Duration.ofMinutes(5), maximumBytes
        );
        return new OAuthClientCredentialsWorkloadTokenProvider(
            properties,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            objectMapper
        );
    }

    private String token(Instant issuedAt, Instant expiresAt) {
        return token(
            endpointStub.issuer().toString(), "opsmind-tool-gateway-workload", "workload",
            issuedAt, expiresAt
        );
    }

    private String token(
        String issuer,
        String audience,
        String tokenUse,
        Instant issuedAt,
        Instant expiresAt
    ) {
        String header = encode(Map.of("alg", "RS256", "typ", "JWT"));
        String payload = encode(Map.of(
            "iss", issuer, "aud", List.of(audience),
            "iat", issuedAt.getEpochSecond(), "exp", expiresAt.getEpochSecond(),
            "token_use", tokenUse, "scope", "tool.execute"
        ));
        return header + "." + payload + ".c2ln";
    }

    private String validToken() {
        return token(NOW.minusSeconds(1), NOW.plusSeconds(120));
    }

    private String tokenResponse(String tokenType, String token) {
        return objectMapper.writeValueAsString(Map.of(
            "access_token", token, "token_type", tokenType,
            "expires_in", 120, "scope", "tool.execute"
        ));
    }

    private String encode(Map<String, Object> value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            objectMapper.writeValueAsBytes(value)
        );
    }

    private void assertInvalid(WorkloadTokenProvider provider) {
        assertThatThrownBy(provider::accessToken)
            .isInstanceOf(WorkloadTokenUnavailableException.class)
            .hasMessage("Workload token endpoint returned an invalid response.");
    }

    private void assertSanitized(WorkloadTokenProvider provider) {
        assertThatThrownBy(provider::accessToken)
            .isInstanceOf(WorkloadTokenUnavailableException.class)
            .hasMessage("Workload token endpoint is unavailable.")
            .hasMessageNotContaining(CLIENT_SECRET).hasMessageNotContaining("padding");
    }
}
