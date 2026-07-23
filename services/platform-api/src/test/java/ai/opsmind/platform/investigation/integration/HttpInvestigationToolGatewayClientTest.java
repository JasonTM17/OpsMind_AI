package ai.opsmind.platform.investigation.integration;

import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.ACTOR_ID;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.NOW;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.catalog;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.context;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.intent;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.mapper;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.properties;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.successResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.delegation.ToolCapabilityGrant;
import ai.opsmind.platform.delegation.ToolCapabilityTokenIssuer;
import ai.opsmind.platform.delegation.WorkloadTokenProvider;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpInvestigationToolGatewayClientTest {

    @Test
    void sendsSeparateCredentialsAndExactCapabilityBoundBytes() throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> capabilityHeader = new AtomicReference<>();
        AtomicReference<ToolCapabilityGrant> grant = new AtomicReference<>();
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capabilityHeader.set(exchange.getRequestHeaders().getFirst(
                "X-OpsMind-Delegated-Capability"
            ));
            byte[] response = objectMapper.writeValueAsBytes(
                successResponse(objectMapper, receivedBody.get(), false)
            );
            ToolGatewayHttpTestServer.respond(exchange, 200, "application/json", response);
        })) {
            WorkloadTokenProvider workload = () -> "workload.jwt.token";
            ToolCapabilityTokenIssuer capability = value -> {
                grant.set(value);
                return "capability.jwt.token";
            };
            HttpInvestigationToolGatewayClient client = client(
                server, objectMapper, catalog, workload, capability
            );

            InvestigationToolGatewayClient.ToolEvidence result = client.execute(
                intent(catalog), context()
            );

            String bodyDigest = java.util.HexFormat.of().formatHex(
                RequestDigest.sha256(receivedBody.get())
            );
            assertThat(authorization.get()).isEqualTo("Bearer workload.jwt.token");
            assertThat(capabilityHeader.get()).isEqualTo("capability.jwt.token");
            assertThat(grant.get().requestDigest()).isEqualTo(bodyDigest);
            assertThat(grant.get().subject()).isEqualTo(ACTOR_ID.toString());
            assertThat(grant.get().action()).isEqualTo("observability:metrics.query:1.0");
            assertThat(grant.get().resource()).isEqualTo("prometheus:synthetic/opsmind-api");
            assertThat(grant.get().deadlineAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(result.sourceType()).isEqualTo("metric");
            assertThat(result.collectedEvidence().gatewayRequestDigest()).isEqualTo(bodyDigest);
            assertThat(result.collectedEvidence().gatewayDuplicate()).isFalse();
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void preservesVerifiedDuplicateStateWithoutIssuingAnotherHttpRequest() throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            ToolGatewayHttpTestServer.respond(
                exchange, 200, "application/json",
                objectMapper.writeValueAsBytes(successResponse(objectMapper, requestBody, true))
            );
        })) {
            InvestigationToolGatewayClient.ToolEvidence result = client(
                server, objectMapper, catalog, () -> "workload.jwt.token", ignored -> "capability.jwt.token"
            ).execute(intent(catalog), context());

            assertThat(result.collectedEvidence().gatewayDuplicate()).isTrue();
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void unknownIntentCannotAcquireCredentialsOrReachHttp() throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        AtomicInteger workloadCalls = new AtomicInteger();
        AtomicInteger capabilityCalls = new AtomicInteger();
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange ->
            ToolGatewayHttpTestServer.respond(exchange, 500, "text/plain", "unexpected"))) {
            WorkloadTokenProvider workload = () -> {
                workloadCalls.incrementAndGet();
                return "workload.jwt.token";
            };
            ToolCapabilityTokenIssuer capability = ignored -> {
                capabilityCalls.incrementAndGet();
                return "capability.jwt.token";
            };
            AnalysisRuntimeResponse.ToolIntent unknown = new AnalysisRuntimeResponse.ToolIntent(
                ToolGatewayClientTestSupport.INTENT_ID,
                "metrics", "query", "sha256:" + "0".repeat(64), "untrusted"
            );

            assertThatThrownBy(() -> client(
                server, objectMapper, catalog, workload, capability
            ).execute(unknown, context())).isInstanceOf(IllegalArgumentException.class);
            assertThat(workloadCalls).hasValue(0);
            assertThat(capabilityCalls).hasValue(0);
            assertThat(server.requestCount()).isZero();
        }
    }

    private HttpInvestigationToolGatewayClient client(
        ToolGatewayHttpTestServer server,
        ObjectMapper objectMapper,
        InvestigationToolIntentCatalog catalog,
        WorkloadTokenProvider workload,
        ToolCapabilityTokenIssuer capability
    ) {
        return new HttpInvestigationToolGatewayClient(
            properties(server.endpoint(), 65_536), workload, capability, catalog,
            new EvidenceContentCanonicalizer(objectMapper), objectMapper, Duration.ofMinutes(2),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
