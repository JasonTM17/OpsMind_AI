package ai.opsmind.platform.incident;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformExceptionHandler;
import ai.opsmind.platform.identity.JwtPrincipalMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import tools.jackson.databind.json.JsonMapper;

class IncidentListControllerHttpTest {

    private static final UUID ORGANIZATION_ID =
        UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID =
        UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String PATH = "/api/v1/organizations/" + ORGANIZATION_ID
        + "/projects/" + PROJECT_ID + "/incidents";

    private IncidentListQueryService queries;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queries = mock(IncidentListQueryService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        JsonMapper mapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
        mvc = MockMvcBuilders.standaloneSetup(
                new IncidentListController(new JwtPrincipalMapper(), queries)
            )
            .setControllerAdvice(new PlatformExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
            .setValidator(validator)
            .build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void returnsExactSummaryShapeWithNoStoreAndBoundParameters() throws Exception {
        IncidentSummary item = new IncidentSummary(
            INCIDENT_ID,
            "API unavailable",
            IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING,
            Instant.parse("2030-01-01T00:00:00Z"),
            3
        );
        when(queries.list(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentStatus.INVESTIGATING),
            eq(2), eq("opaque_token")
        )).thenReturn(new IncidentListPage(List.of(item), 2, null, false));

        mvc.perform(get(PATH)
                .principal(authentication())
                .queryParam("status", "INVESTIGATING")
                .queryParam("pageSize", "2")
                .queryParam("pageToken", "opaque_token"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.items[0].id").value(INCIDENT_ID.toString()))
            .andExpect(jsonPath("$.items[0].title").value("API unavailable"))
            .andExpect(jsonPath("$.items[0].severity").value("SEV1"))
            .andExpect(jsonPath("$.items[0].status").value("INVESTIGATING"))
            .andExpect(jsonPath("$.items[0].updatedAt").value("2030-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.items[0].version").value(3))
            .andExpect(jsonPath("$.items[0].summary").doesNotExist())
            .andExpect(jsonPath("$.items[0].rootCause").doesNotExist())
            .andExpect(jsonPath("$.items[0].createdBy").doesNotExist())
            .andExpect(jsonPath("$.items[0].organizationId").doesNotExist())
            .andExpect(jsonPath("$.nextPageToken").doesNotExist());

        verify(queries).list(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentStatus.INVESTIGATING),
            eq(2), eq("opaque_token")
        );
    }

    @Test
    void invalidStatusPageSizeAndBlankTokenFailBeforeQuery() throws Exception {
        for (String query : List.of(
            "status=NOT_A_STATUS",
            "pageSize=0",
            "pageSize=101",
            "pageToken="
        )) {
            mvc.perform(get(PATH + "?" + query).principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }
        verify(queries, never()).list(any(), any(), any(), any(), any(Integer.class), any());
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
