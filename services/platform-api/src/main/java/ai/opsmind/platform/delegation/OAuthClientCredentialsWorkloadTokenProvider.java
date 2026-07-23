package ai.opsmind.platform.delegation;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import tools.jackson.databind.ObjectMapper;

final class OAuthClientCredentialsWorkloadTokenProvider implements WorkloadTokenProvider {

    private final OAuthClientCredentialsProperties properties;
    private final Clock clock;
    private final OAuthTokenHttpExchange exchange;
    private final WorkloadAccessTokenValidator validator;
    private final String authorization;
    private final byte[] requestBody;
    private WorkloadAccessToken cached;

    OAuthClientCredentialsWorkloadTokenProvider(
        OAuthClientCredentialsProperties properties,
        HttpClient httpClient,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        properties.validateEnabled();
        this.properties = properties;
        this.clock = clock;
        this.exchange = new OAuthTokenHttpExchange(httpClient, properties.maximumResponseBodyBytes());
        this.validator = new WorkloadAccessTokenValidator(properties, objectMapper);
        this.authorization = basicAuthorization(properties.clientId(), properties.clientSecret());
        this.requestBody = ("grant_type=client_credentials&scope=" + encode(properties.scope()))
            .getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().minus(properties.refreshSkew()).isAfter(now)) {
            return cached.value();
        }
        HttpRequest request = HttpRequest.newBuilder(properties.tokenEndpoint())
            .timeout(properties.requestTimeout())
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .build();
        byte[] response = exchange.send(request, properties.requestTimeout());
        WorkloadAccessToken refreshed = validator.validate(response, clock.instant());
        cached = refreshed;
        return refreshed.value();
    }

    private String basicAuthorization(String clientId, String clientSecret) {
        String credentials = encode(clientId) + ":" + encode(clientSecret);
        return "Basic " + Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
