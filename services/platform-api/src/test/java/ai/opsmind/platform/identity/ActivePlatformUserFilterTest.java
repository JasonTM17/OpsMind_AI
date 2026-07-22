package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ActivePlatformUserFilterTest {

    private final PlatformUserStatusVerifier statusVerifier = mock(PlatformUserStatusVerifier.class);
    private final SecurityProblemWriter problemWriter = mock(SecurityProblemWriter.class);
    private final ActivePlatformUserFilter filter = new ActivePlatformUserFilter(
        new JwtPrincipalMapper(),
        statusVerifier,
        problemWriter
    );
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token(), List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void downstreamIllegalArgumentExceptionIsNotRelabeledAsAuthenticationFailure() throws Exception {
        var downstream = new IllegalArgumentException("downstream contract failure");
        org.mockito.Mockito.doThrow(downstream).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
            .isSameAs(downstream);
        verify(problemWriter, never()).write(
            request,
            response,
            HttpStatus.UNAUTHORIZED,
            "identity.claims-invalid",
            "The access token claims are not acceptable."
        );
    }

    @Test
    void downstreamPlatformProblemIsNotConsumedByIdentityFilter() throws Exception {
        var downstream = new PlatformProblemException(
            HttpStatus.CONFLICT,
            "incident.conflict",
            "The incident changed concurrently."
        );
        org.mockito.Mockito.doThrow(downstream).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
            .isSameAs(downstream);
        verify(problemWriter, never()).write(
            request,
            response,
            HttpStatus.CONFLICT,
            "incident.conflict",
            "The incident changed concurrently."
        );
    }

    @Test
    void mapperRejectionWritesDenialWithoutInvokingDownstreamChain() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(tokenWithInvalidDisplayName(), List.of())
        );

        filter.doFilterInternal(request, response, chain);

        verify(problemWriter).write(
            request,
            response,
            HttpStatus.UNAUTHORIZED,
            "identity.claims-invalid",
            "The access token claims are not acceptable."
        );
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void servletContextPathCannotBypassActiveUserVerification() throws Exception {
        when(request.getRequestURI()).thenReturn("/opsmind/api/v1/incidents");
        when(request.getContextPath()).thenReturn("/opsmind");
        when(request.getServletPath()).thenReturn("/api/v1/incidents");

        filter.doFilter(request, response, chain);

        verify(statusVerifier).requireActive(org.mockito.ArgumentMatchers.any(OpsMindPrincipal.class));
        verify(chain).doFilter(request, response);
    }

    private Jwt token() {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        return Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .audience(List.of("opsmind-platform-api"))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .claim("amr", List.of("pwd", "mfa"))
            .build();
    }

    private Jwt tokenWithInvalidDisplayName() {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        return Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .audience(List.of("opsmind-platform-api"))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .claim("name", "x".repeat(256))
            .claim("amr", List.of("pwd", "mfa"))
            .build();
    }
}
