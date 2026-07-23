package ai.opsmind.platform.investigation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.OperatorProjection;
import ai.opsmind.platform.common.api.PlatformExceptionHandler;
import ai.opsmind.platform.identity.JwtPrincipalMapper;
import ai.opsmind.platform.investigation.application.InvestigationRunService;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.projection.InvestigationRunReadModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InvestigationRunControllerHttpTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID EVIDENCE_ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/organizations/" + ORGANIZATION_ID
        + "/projects/" + PROJECT_ID + "/incidents/" + INCIDENT_ID
        + "/investigations/" + RUN_ID;

    private InvestigationRunService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(InvestigationRunService.class);
        mvc = MockMvcBuilders.standaloneSetup(
            new InvestigationRunController(new JwtPrincipalMapper(), service)
        ).setControllerAdvice(new PlatformExceptionHandler()).build();
        when(service.get(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID), eq(RUN_ID)
        )).thenReturn(readModel());
    }

    @Test
    void vendorRepresentationWithholdsModelProseAndEmitsAssurance() throws Exception {
        mvc.perform(get(PATH)
                .principal(authentication())
                .header(HttpHeaders.ACCEPT, OperatorProjection.MEDIA_TYPE_VALUE))
            .andExpect(status().isOk())
            .andExpect(content().contentType(OperatorProjection.MEDIA_TYPE))
            .andExpect(header().string(
                OperatorProjection.CLASSIFICATION_HEADER, OperatorProjection.CLASSIFICATION
            ))
            .andExpect(header().string(
                OperatorProjection.REDACTION_VERSION_HEADER, OperatorProjection.REDACTION_VERSION
            ))
            .andExpect(header().string(OperatorProjection.REDACTION_COUNT_HEADER, "6"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.VARY, "Accept"))
            .andExpect(jsonPath("$.analysis.model_id").value("platform-analysis-adapter"))
            .andExpect(jsonPath("$.analysis.hypotheses[0].title")
                .value("Evidence-backed hypothesis 1"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Raw provider")
            )));
    }

    @Test
    void jsonAndWildcardRemainLegacyWithoutAssurance() throws Exception {
        assertLegacy(MediaType.APPLICATION_JSON_VALUE);
        assertLegacy(MediaType.ALL_VALUE);
    }

    @Test
    void preferenceSelectsJsonAndUnacceptableVendorFailsWith406() throws Exception {
        assertLegacy(
            MediaType.APPLICATION_JSON_VALUE + ";q=1, "
                + OperatorProjection.MEDIA_TYPE_VALUE + ";q=0.2"
        );

        mvc.perform(get(PATH)
                .principal(authentication())
                .header(HttpHeaders.ACCEPT, OperatorProjection.MEDIA_TYPE_VALUE + ";q=0"))
            .andExpect(status().isNotAcceptable())
            .andExpect(jsonPath("$.code")
                .value("request.response-media-type-unacceptable"));
    }

    private void assertLegacy(String accept) throws Exception {
        mvc.perform(get(PATH)
                .principal(authentication())
                .header(HttpHeaders.ACCEPT, accept))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist(OperatorProjection.CLASSIFICATION_HEADER))
            .andExpect(header().doesNotExist(OperatorProjection.REDACTION_VERSION_HEADER))
            .andExpect(header().doesNotExist(OperatorProjection.REDACTION_COUNT_HEADER))
            .andExpect(jsonPath("$.analysis.hypotheses[0].title").value("Raw provider title"));
    }

    private InvestigationRunReadModel readModel() {
        String digest = "sha256:" + "a".repeat(64);
        AnalysisRuntimeResponse.Citation citation = new AnalysisRuntimeResponse.Citation(
            EVIDENCE_ID, digest, "Raw provider claim"
        );
        AnalysisRuntimeResponse analysis = new AnalysisRuntimeResponse(
            "complete", RUN_ID, "raw-provider-model", "prompt-incident-investigation-v1",
            "analysis-v1",
            List.of(new AnalysisRuntimeResponse.Hypothesis(
                "Raw provider title", "Raw provider explanation", 0.8, List.of(citation)
            )),
            List.of("Raw provider counter evidence"), List.of(), List.of(citation), 0.8,
            new AnalysisRuntimeResponse.Usage(10, 5, 15),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ONE),
            List.of()
        );
        return new InvestigationRunReadModel(
            RUN_ID, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID,
            InvestigationStateMachine.Status.COMPLETED,
            new InvestigationRunReadModel.BudgetView(4, 4, 20, 8_000),
            2, 1, 15, List.of(EVIDENCE_ID), List.of(), analysis, null,
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:10:00Z"),
            Instant.parse("2030-01-01T00:02:00Z")
        );
    }

    private JwtAuthenticationToken authentication() {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        Jwt jwt = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .audience(List.of("opsmind-platform-api"))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .claim("scope", String.join(" ", Set.of("incident:read")))
            .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
