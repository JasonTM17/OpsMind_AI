package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import ai.opsmind.platform.common.api.CorrelationIdFilter;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.JwtPrincipalMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class IncidentControllerTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OPERATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final IncidentMutationService mutations = mock(IncidentMutationService.class);
    private final IncidentQueryService queries = mock(IncidentQueryService.class);
    private final IncidentController controller = new IncidentController(
        new JwtPrincipalMapper(), mutations, queries
    );
    private final HttpServletRequest servletRequest = mock(HttpServletRequest.class);

    @Test
    void createReturnsExactMutationHeadersAndJsonBody() {
        var request = new CreateIncidentRequest("API down", "5xx spike", IncidentSeverity.SEV1, "alert");
        URI location = URI.create("/api/v1/organizations/" + ORGANIZATION_ID + "/projects/"
            + PROJECT_ID + "/incidents/" + INCIDENT_ID);
        when(servletRequest.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME)).thenReturn("trace_12345678");
        when(mutations.create(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(ORGANIZATION_ID),
            org.mockito.ArgumentMatchers.eq(PROJECT_ID),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(request),
            org.mockito.ArgumentMatchers.eq("trace_12345678")
        )).thenReturn(new IncidentOperationResult(201, "{\"version\":0}", location, "\"0\"", OPERATION_ID));

        var response = controller.create(
            authentication(Set.of("incident:write")), ORGANIZATION_ID, PROJECT_ID,
            "create-incident-001", request, servletRequest
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(location.toString());
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"0\"");
        assertThat(response.getHeaders().getFirst(IncidentController.OPERATION_ID_HEADER))
            .isEqualTo(OPERATION_ID.toString());
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"version\":0}");
    }

    @Test
    void rejectsMissingOrMalformedConcurrencyHeadersBeforeMutation() {
        var request = new TransitionIncidentRequest(
            IncidentStatus.INVESTIGATING, "triage started", null, null
        );
        assertThatThrownBy(() -> controller.transition(
            authentication(Set.of("incident:write")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID,
            "transition-001", null, request, servletRequest
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
            assertThat(exception.code()).isEqualTo("request.if-match-required");
        });
        assertThatThrownBy(() -> controller.transition(
            authentication(Set.of("incident:write")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID,
            "transition-001", "W/\"0\"", request, servletRequest
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("request.if-match-invalid"));
        verify(mutations, never()).transition(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void detailReturnsCurrentEtagAndUnsupportedAuthenticationFailsClosed() {
        IncidentResponse body = response(7);
        when(queries.detail(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(ORGANIZATION_ID),
            org.mockito.ArgumentMatchers.eq(PROJECT_ID),
            org.mockito.ArgumentMatchers.eq(INCIDENT_ID)
        )).thenReturn(new IncidentDetailResult(body, "\"7\""));

        var response = controller.detail(
            authentication(Set.of("incident:read")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        );
        assertThat(response.getBody()).isEqualTo(body);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"7\"");
        assertThatThrownBy(() -> controller.detail(null, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private JwtAuthenticationToken authentication(Set<String> scopes) {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        Jwt jwt = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .audience(List.of("opsmind-platform-api"))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .claim("scope", String.join(" ", scopes))
            .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private IncidentResponse response(long version) {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        return new IncidentResponse(
            INCIDENT_ID, ORGANIZATION_ID, PROJECT_ID, "API down", "5xx spike",
            IncidentSeverity.SEV1, IncidentStatus.INVESTIGATING, null, null,
            OPERATION_ID, OPERATION_ID, now, now, version
        );
    }
}
