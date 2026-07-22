package ai.opsmind.platform.incident;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.CorrelationIdFilter;
import ai.opsmind.platform.common.api.JsonRequestBodyLimitFilter;
import ai.opsmind.platform.common.api.PlatformExceptionHandler;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.PlatformProblemWriter;
import ai.opsmind.platform.identity.JwtPrincipalMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class IncidentControllerHttpTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OPERATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String COLLECTION_PATH = "/api/v1/organizations/" + ORGANIZATION_ID
        + "/projects/" + PROJECT_ID + "/incidents";
    private static final String TRANSITION_PATH = COLLECTION_PATH + "/" + INCIDENT_ID + "/transitions";
    private static final String TIMELINE_PATH = COLLECTION_PATH + "/" + INCIDENT_ID + "/timeline";

    private IncidentMutationService mutations;
    private IncidentQueryService queries;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mutations = mock(IncidentMutationService.class);
        queries = mock(IncidentQueryService.class);
        var controller = new IncidentController(new JwtPrincipalMapper(), mutations, queries);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new PlatformExceptionHandler())
            .setMessageConverters(
                new ByteArrayHttpMessageConverter(),
                new JacksonJsonHttpMessageConverter(mapper)
            )
            .setValidator(validator)
            .addFilters(
                new CorrelationIdFilter(),
                new JsonRequestBodyLimitFilter(new PlatformProblemWriter(mapper), 1_024)
            )
            .build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void createBindsHttpRequestAndReturnsContractHeaders() throws Exception {
        URI location = URI.create(COLLECTION_PATH + "/" + INCIDENT_ID);
        when(mutations.create(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), any(), any(), anyString()))
            .thenReturn(new IncidentOperationResult(
                201,
                "{\"id\":\"" + INCIDENT_ID + "\",\"status\":\"OPEN\",\"version\":0}",
                location,
                "\"0\"",
                OPERATION_ID
            ));

        mvc.perform(post(COLLECTION_PATH)
                .principal(authentication(Set.of("incident:write")))
                .header("Idempotency-Key", "create-incident-001")
                .header(CorrelationIdFilter.HEADER_NAME, "trace_12345678")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateBody()))
            .andExpect(status().isCreated())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.LOCATION, location.toString()))
            .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
            .andExpect(header().string(IncidentController.OPERATION_ID_HEADER, OPERATION_ID.toString()))
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "trace_12345678"))
            .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void unknownBodyPropertyFailsClosedBeforeMutation() throws Exception {
        String body = """
            {
              "title":"API down",
              "summary":"5xx spike",
              "severity":"SEV1",
              "reason":"alert",
              "organizationId":"11111111-1111-4111-8111-111111111111"
            }
            """;

        mvc.perform(post(COLLECTION_PATH)
                .principal(authentication(Set.of("incident:write")))
                .header("Idempotency-Key", "create-incident-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("request.body-invalid"))
            .andExpect(jsonPath("$.status").value(400));

        verify(mutations, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void malformedJsonAndInvalidEnumReturnSanitizedBadRequest() throws Exception {
        assertInvalidBody("{\"title\":", "malformed-body-001");
        assertInvalidBody(validCreateBody().replace("SEV1", "SEV0"), "invalid-enum-001");

        verify(mutations, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidUuidReturnsSanitizedBadRequestBeforeQuery() throws Exception {
        mvc.perform(get("/api/v1/organizations/not-a-uuid/projects/" + PROJECT_ID
                + "/incidents/" + INCIDENT_ID)
                .principal(authentication(Set.of("incident:read"))))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("request.parameter-invalid"))
            .andExpect(jsonPath("$.detail").value("A path, query, or header parameter is invalid."));

        verify(queries, never()).detail(any(), any(), any(), any());
    }

    @Test
    void hiddenResourceNotFoundDoesNotReflectScopedIdentifiers() throws Exception {
        when(queries.detail(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID)))
            .thenThrow(new PlatformProblemException(
                HttpStatus.NOT_FOUND,
                "resource.not-found",
                "The requested resource does not exist or is not visible."
            ));

        mvc.perform(get(COLLECTION_PATH + "/" + INCIDENT_ID)
                .principal(authentication(Set.of("incident:read")))
                .header(CorrelationIdFilter.HEADER_NAME, "trace_hidden_001"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("resource.not-found"))
            .andExpect(jsonPath("$.instance").value("urn:opsmind:problem:trace_hidden_001"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString(INCIDENT_ID.toString())
            )))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString(ORGANIZATION_ID.toString())
            )));
    }

    @Test
    void unsupportedRequestMediaTypeReturns415BeforeMutation() throws Exception {
        mvc.perform(post(COLLECTION_PATH)
                .principal(authentication(Set.of("incident:write")))
                .header("Idempotency-Key", "create-incident-001")
                .contentType(MediaType.TEXT_PLAIN)
                .content(validCreateBody()))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("request.media-type-unsupported"));

        verify(mutations, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void oversizedJsonBodyReturns413BeforeDeserializationOrMutation() throws Exception {
        String body = "{\"title\":\"" + "x".repeat(1_100) + "\"}";

        mvc.perform(post(COLLECTION_PATH)
                .principal(authentication(Set.of("incident:write")))
                .header("Idempotency-Key", "oversized-body-001")
                .header(CorrelationIdFilter.HEADER_NAME, "trace_oversized_001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isContentTooLarge())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("request.body-too-large"))
            .andExpect(jsonPath("$.status").value(413))
            .andExpect(jsonPath("$.traceId").value("trace_oversized_001"));

        verify(mutations, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void transitionBindsConditionalRequestAndReturnsCanonicalJson() throws Exception {
        when(mutations.transition(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID), any(), eq(0L), any(), any()
        )).thenReturn(new IncidentOperationResult(
            200,
            "{\"id\":\"" + INCIDENT_ID + "\",\"status\":\"INVESTIGATING\",\"version\":1}",
            null,
            "\"1\"",
            OPERATION_ID
        ));

        mvc.perform(post(TRANSITION_PATH)
                .principal(authentication(Set.of("incident:write")))
                .header("Idempotency-Key", "transition-incident-001")
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "targetStatus":"INVESTIGATING",
                      "reason":"Triage started"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
            .andExpect(header().string(IncidentController.OPERATION_ID_HEADER, OPERATION_ID.toString()))
            .andExpect(jsonPath("$.status").value("INVESTIGATING"))
            .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void transitionConditionalSchemaRejectsSuppliedOrMissingResolutionFields() throws Exception {
        assertInvalidTransitionBody("""
            {"targetStatus":"INVESTIGATING","reason":"triage","rootCause":null}
            """, "transition-null-resolution-001");
        assertInvalidTransitionBody("""
            {"targetStatus":"INVESTIGATING","reason":"triage","rootCause":"   "}
            """, "transition-blank-resolution-001");
        assertInvalidTransitionBody("""
            {"targetStatus":"RESOLVED","reason":"fixed","rootCause":"dependency saturation"}
            """, "transition-missing-summary-001");

        verify(mutations, never()).transition(any(), any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void terminalTimelineOmitsAbsentNextPageToken() throws Exception {
        when(queries.timeline(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID), eq(25),
            org.mockito.ArgumentMatchers.isNull()
        )).thenReturn(new IncidentTimelinePage(List.of(), 25, null, false));

        mvc.perform(get(TIMELINE_PATH)
                .principal(authentication(Set.of("incident:read"))))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.pageSize").value(25))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextPageToken").doesNotExist());
    }

    private void assertInvalidBody(String body, String idempotencyKey) throws Exception {
        mvc.perform(post(COLLECTION_PATH)
                .principal(authentication(Set.of("incident:write")))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("request.body-invalid"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("tools.jackson")
            )));
    }

    private void assertInvalidTransitionBody(String body, String idempotencyKey) throws Exception {
        mvc.perform(post(TRANSITION_PATH)
                .principal(authentication(Set.of("incident:write")))
                .header("Idempotency-Key", idempotencyKey)
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("request.body-invalid"));
    }

    private String validCreateBody() {
        return """
            {
              "title":"API down",
              "summary":"5xx spike",
              "severity":"SEV1",
              "reason":"alert"
            }
            """;
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
}
