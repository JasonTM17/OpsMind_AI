package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

class IncidentAnalysisServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ACTOR_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private final IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
    private final AnalysisEvidenceResolver evidenceResolver = mock(AnalysisEvidenceResolver.class);
    private final AnalysisCapabilityTokenIssuer issuer = mock(AnalysisCapabilityTokenIssuer.class);
    private final AnalysisRuntimeClient client = mock(AnalysisRuntimeClient.class);
    private final IncidentAnalysisService service = new IncidentAnalysisService(
        authorizer,
        evidenceResolver,
        new AnalysisRequestCanonicalizer(new ObjectMapper()),
        issuer,
        client
    );

    @Test
    void authorizesThenBindsExactDigestAndInternalActorToCapability() {
        StartIncidentAnalysisRequest request = request(Instant.now().plusSeconds(60));
        AnalysisRuntimeResponse expected = response();
        AuthorizedIncidentAnalysisEvidence authorizedEvidence = authorizedEvidence();
        when(authorizer.requireEvidence(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID)))
            .thenReturn(authorizedEvidence);
        when(evidenceResolver.resolve(
            authorizedEvidence,
            "investigate",
            "incident_investigation"
        )).thenReturn(evidence());
        when(issuer.issue(any())).thenReturn("header.payload.signature");
        when(client.analyze(any(), eq("header.payload.signature"), eq("trace_analysis_001")))
            .thenReturn(expected);

        AnalysisRuntimeResponse result = service.analyze(
            principal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, request, "trace_analysis_001"
        );

        ArgumentCaptor<AnalysisCapabilityGrant> grant = ArgumentCaptor.forClass(
            AnalysisCapabilityGrant.class
        );
        ArgumentCaptor<PreparedAnalysisRequest> prepared = ArgumentCaptor.forClass(
            PreparedAnalysisRequest.class
        );
        verify(issuer).issue(grant.capture());
        verify(client).analyze(
            prepared.capture(), eq("header.payload.signature"), eq("trace_analysis_001")
        );
        assertThat(result).isEqualTo(expected);
        assertThat(grant.getValue().subject()).isEqualTo(ACTOR_ID.toString());
        assertThat(grant.getValue().tenantId()).isEqualTo(ORGANIZATION_ID);
        assertThat(grant.getValue().requestDigest()).isEqualTo(prepared.getValue().requestDigest());
        assertThat(grant.getValue().allowedDataClasses()).containsExactly("redacted_metrics");
        verify(evidenceResolver).resolve(
            authorizedEvidence,
            "investigate",
            "incident_investigation"
        );
    }

    @Test
    void accessDenialStopsBeforeSigningOrNetwork() {
        when(authorizer.requireEvidence(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID)))
            .thenThrow(new PlatformProblemException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "resource.not-found",
                "hidden"
            ));

        assertThatThrownBy(() -> service.analyze(
            principal(), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID,
            request(Instant.now().plusSeconds(60)), "trace_analysis_002"
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("resource.not-found"));
        verify(issuer, never()).issue(any());
        verify(client, never()).analyze(any(), any(), any());
        verify(evidenceResolver, never()).resolve(any(), any(), any());
    }

    private StartIncidentAnalysisRequest request(Instant deadline) {
        return new StartIncidentAnalysisRequest(
            RUN_ID,
            "investigate",
            "incident_investigation",
            1_000,
            0,
            deadline
        );
    }

    private ResolvedAnalysisEvidence evidence() {
        return new ResolvedAnalysisEvidence(
            "Investigate redacted latency metrics.",
            "prompt-incident-v1",
            List.of(),
            List.of("redacted_metrics")
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorizedEvidence() {
        return new AuthorizedIncidentAnalysisEvidence(
            ORGANIZATION_ID,
            PROJECT_ID,
            INCIDENT_ID,
            ACTOR_ID,
            "API unavailable",
            "Redacted latency spike",
            IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING,
            null,
            null,
            2
        );
    }

    private OpsMindPrincipal principal() {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "operator-001",
            null,
            null,
            Set.of("incident:analyze")
        );
    }

    private AnalysisRuntimeResponse response() {
        return new AnalysisRuntimeResponse(
            "abstain",
            RUN_ID,
            "deepseek-v4-flash",
            "prompt-incident-v1",
            "analysis-v1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0.0,
            new AnalysisRuntimeResponse.Usage(1, 0, 1),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO),
            List.of()
        );
    }
}
