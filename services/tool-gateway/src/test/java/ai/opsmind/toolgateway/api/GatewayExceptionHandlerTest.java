package ai.opsmind.toolgateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.application.ToolExecutionService;
import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.audit.ToolExecutionProvenance;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.servlet.Filter;

class GatewayExceptionHandlerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedMalformedHttpRequestAppendsOneTenantFreeAudit() throws Exception {
        TrackingAuditWriter audit = new TrackingAuditWriter();
        GatewaySettings settings = settings();
        ToolExecutionService executionService = mock(ToolExecutionService.class);
        GatewayExceptionHandler handler = new GatewayExceptionHandler(audit, settings);
        JwtAuthenticationToken authentication = platformAuthentication();
        Filter securityContext = (request, response, chain) -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                chain.doFilter(request, response);
            }
            finally {
                SecurityContextHolder.clearContext();
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ToolExecutionController(executionService, settings)
        )
            .setControllerAdvice(handler)
            .addFilters(securityContext)
            .build();

        mvc.perform(post("/internal/v1/tools/execute")
            .header(ToolExecutionController.CAPABILITY_HEADER, "not-evaluated")
            .contentType("application/json")
            .content("{\"execution_id\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("request.invalid"));

        assertThat(audit.unverifiedAppends).isEqualTo(1);
        assertThat(audit.lastExecutionId).isNull();
        assertThat(audit.lastDenialCode).isEqualTo(DenialCode.REQUEST_INVALID);
        verifyNoInteractions(executionService);
    }

    @Test
    void unauthenticatedMalformedRequestCannotFillTheAuditLane() {
        TrackingAuditWriter audit = new TrackingAuditWriter();
        GatewayExceptionHandler handler = new GatewayExceptionHandler(audit, settings());

        handler.malformedJson();

        assertThat(audit.unverifiedAppends).isZero();
    }

    @Test
    void authenticatedMissingCapabilityHeaderUsesUnverifiedAudit() {
        TrackingAuditWriter audit = new TrackingAuditWriter();
        GatewayExceptionHandler handler = new GatewayExceptionHandler(audit, settings());
        SecurityContextHolder.getContext().setAuthentication(platformAuthentication());

        var response = handler.missingHeader();

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("code", "capability.invalid");
        assertThat(audit.unverifiedAppends).isEqualTo(1);
        assertThat(audit.lastDenialCode).isEqualTo(DenialCode.CAPABILITY_INVALID);
    }

    @Test
    void authenticatedDeliveryRejectionFailsClosedWhenAuditIsUnavailable() {
        TrackingAuditWriter audit = new TrackingAuditWriter();
        audit.available = false;
        GatewayExceptionHandler handler = new GatewayExceptionHandler(audit, settings());
        SecurityContextHolder.getContext().setAuthentication(platformAuthentication());

        var response = handler.malformedJson();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("code", "audit.unavailable");
        assertThat(audit.unverifiedAppends).isZero();
    }

    private JwtAuthenticationToken platformAuthentication() {
        Jwt jwt = Jwt.withTokenValue("test-workload-token")
            .header("alg", "RS256")
            .subject("opsmind-platform-api")
            .claim("client_id", "opsmind-platform-api")
            .issuedAt(Instant.now().minusSeconds(5))
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        return new JwtAuthenticationToken(
            jwt,
            java.util.List.of(new SimpleGrantedAuthority("SCOPE_tool.execute")),
            "opsmind-platform-api"
        );
    }

    private GatewaySettings settings() {
        return new GatewaySettings(
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway",
            "opsmind-platform-api",
            null,
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload",
            "tool.execute",
            null,
            Duration.ofMinutes(5),
            65_536,
            262_144
        );
    }

    private static final class TrackingAuditWriter implements ToolAuditWriter {

        private int unverifiedAppends;
        private UUID lastExecutionId;
        private DenialCode lastDenialCode;
        private boolean available = true;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public UUID recordScoped(
            TenantProjectScope scope,
            UUID executionId,
            ToolOutcome outcome,
            String requestDigest,
            String capabilityId,
            String manifestVersion,
            ToolExecutionProvenance provenance,
            String resultDigest,
            String policyVersion,
            DenialCode denialCode
        ) {
            throw new AssertionError("Delivery rejection cannot use scoped audit.");
        }

        @Override
        public UUID recordUnverified(
            UUID executionId,
            ToolOutcome outcome,
            String requestDigest,
            DenialCode denialCode
        ) {
            unverifiedAppends++;
            lastExecutionId = executionId;
            lastDenialCode = denialCode;
            return UUID.randomUUID();
        }
    }
}
