package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.CorrelationIdFilter;
import ai.opsmind.platform.common.api.JsonRequestBodyLimitFilter;
import ai.opsmind.platform.common.api.PlatformExceptionHandler;
import ai.opsmind.platform.common.api.PlatformProblemWriter;
import ai.opsmind.platform.identity.JwtPrincipalMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class IncidentAnalysisControllerHttpTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String PATH = "/api/v1/organizations/" + ORGANIZATION_ID
        + "/projects/" + PROJECT_ID + "/incidents/" + INCIDENT_ID + "/analysis";

    private IncidentAnalysisService service;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(IncidentAnalysisService.class);
        var controller = new IncidentAnalysisController(new JwtPrincipalMapper(), service);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new PlatformExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
            .setValidator(validator)
            .addFilters(
                new CorrelationIdFilter(),
                new JsonRequestBodyLimitFilter(new PlatformProblemWriter(mapper), 65_536)
            )
            .build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void bindsSnakeCaseRequestVerifiedPrincipalAndCorrelationId() throws Exception {
        when(service.analyze(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID), any(),
            eq("trace_analysis_001"))).thenReturn(response());

        mvc.perform(post(PATH)
                .principal(authentication())
                .header(CorrelationIdFilter.HEADER_NAME, "trace_analysis_001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("")))
            .andExpect(status().isOk())
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "trace_analysis_001"))
            .andExpect(jsonPath("$.run_id").value(RUN_ID.toString()))
            .andExpect(jsonPath("$.schema_version").value("analysis-v1"));

        ArgumentCaptor<StartIncidentAnalysisRequest> request = ArgumentCaptor.forClass(
            StartIncidentAnalysisRequest.class
        );
        verify(service).analyze(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID), request.capture(),
            eq("trace_analysis_001")
        );
        assertThat(request.getValue().runId()).isEqualTo(RUN_ID);
        assertThat(request.getValue().analysisMode()).isEqualTo("investigate");
    }

    @Test
    void rejectsUnknownRequestFieldsBeforeServiceInvocation() throws Exception {
        mvc.perform(post(PATH)
                .principal(authentication())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(",\"tenant_id\":\"" + ORGANIZATION_ID
                    + "\",\"prompt\":\"caller-controlled evidence\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("request.body-invalid"));
    }

    @Test
    void rejectsOmittedRequiredBudgetsBeforeServiceInvocation() throws Exception {
        mvc.perform(post(PATH)
                .principal(authentication())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("").replace(",\"tool_budget\":0", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("request.body-invalid"));

        verifyNoInteractions(service);
    }

    private JwtAuthenticationToken authentication() {
        Instant issuedAt = Instant.now();
        Jwt jwt = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .audience(List.of("opsmind-platform-api"))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .claim("scope", "incident:analyze")
            .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private String requestJson(String extra) {
        return "{\"run_id\":\"" + RUN_ID + "\",\"analysis_mode\":\"investigate\","
            + "\"purpose\":\"incident_investigation\",\"token_budget\":1000,"
            + "\"tool_budget\":0,\"deadline_at\":\"2099-01-01T00:00:00Z\"" + extra + "}";
    }

    private AnalysisRuntimeResponse response() {
        return new AnalysisRuntimeResponse(
            "abstain", RUN_ID, "deepseek-v4-flash", "prompt-incident-v1", "analysis-v1",
            List.of(), List.of(), List.of(), List.of(), 0.0,
            new AnalysisRuntimeResponse.Usage(1, 0, 1),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO),
            List.of()
        );
    }
}
