package ai.opsmind.platform.delegation;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.core.StreamReadFeature;

final class WorkloadAccessTokenValidator {

    private static final String INVALID = "Workload token endpoint returned an invalid response.";
    private static final Set<String> REQUIRED_RESPONSE_FIELDS = Set.of(
        "access_token", "token_type", "expires_in"
    );
    private static final Set<String> ALLOWED_RESPONSE_FIELDS = Set.of(
        "access_token", "token_type", "expires_in", "scope"
    );

    private final OAuthClientCredentialsProperties properties;
    private final ObjectReader responseReader;
    private final ObjectReader claimsReader;

    WorkloadAccessTokenValidator(
        OAuthClientCredentialsProperties properties,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.responseReader = objectMapper.reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.claimsReader = objectMapper.reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    WorkloadAccessToken validate(byte[] body, Instant now) {
        try {
            JsonNode response = responseReader.readTree(body);
            Set<String> fields = Set.copyOf(response.propertyNames());
            JsonNode expiresInNode = response.path("expires_in");
            String accessToken = text(response, "access_token");
            String tokenType = text(response, "token_type");
            long expiresIn = expiresInNode.isIntegralNumber() && expiresInNode.canConvertToLong()
                ? expiresInNode.asLong() : -1;
            if (!response.isObject() || !fields.containsAll(REQUIRED_RESPONSE_FIELDS)
                || !ALLOWED_RESPONSE_FIELDS.containsAll(fields)
                || !"Bearer".equalsIgnoreCase(tokenType)
                || accessToken.isBlank() || accessToken.length() > 16_384
                || accessToken.chars().anyMatch(Character::isWhitespace)
                || expiresIn < 1 || expiresIn > properties.maximumTokenLifetime().toSeconds()
                || response.has("scope") && !properties.scope().equals(text(response, "scope"))) {
                throw invalid();
            }
            JsonNode claims = claims(accessToken);
            long issuedAt = requiredEpochSecond(claims, "iat");
            long expiresAt = requiredEpochSecond(claims, "exp");
            Instant issued = Instant.ofEpochSecond(issuedAt);
            Instant jwtExpiry = Instant.ofEpochSecond(expiresAt);
            Instant responseExpiry = now.plusSeconds(expiresIn);
            Instant maximumExpiry = now.plus(properties.maximumTokenLifetime());
            Instant effectiveExpiry = jwtExpiry.isBefore(responseExpiry) ? jwtExpiry : responseExpiry;
            if (!properties.canonicalIssuer().equals(claims.path("iss").asString())
                || !exactAudience(claims.path("aud"))
                || !"workload".equals(claims.path("token_use").asString())
                || !properties.scope().equals(claims.path("scope").asString())
                || issued.isAfter(now.plusSeconds(30)) || !jwtExpiry.isAfter(issued)
                || jwtExpiry.isAfter(maximumExpiry)
                || !effectiveExpiry.isAfter(now.plus(properties.refreshSkew()))) {
                throw invalid();
            }
            return new WorkloadAccessToken(accessToken, effectiveExpiry);
        }
        catch (JacksonException | IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    private JsonNode claims(String token) throws JacksonException {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3 || segments[1].isEmpty()) throw invalid();
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(segments[1]);
        }
        catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (payload.length == 0 || payload.length > 12_288) throw invalid();
        return claimsReader.readTree(payload);
    }

    private long requiredEpochSecond(JsonNode claims, String name) {
        JsonNode value = claims.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.asLong();
    }

    private boolean exactAudience(JsonNode value) {
        return value.isArray() && value.size() == 1
            && List.of(properties.audience()).equals(List.of(value.get(0).asString()));
    }

    private String text(JsonNode object, String name) {
        JsonNode value = object.path(name);
        return value.isString() ? value.asString() : "";
    }

    private WorkloadTokenUnavailableException invalid() {
        return new WorkloadTokenUnavailableException(INVALID);
    }
}
