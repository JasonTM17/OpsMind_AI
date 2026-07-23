package ai.opsmind.toolgateway.config;

import java.time.Clock;
import java.util.List;

import ai.opsmind.toolgateway.application.BoundedConnectorExecutor;
import ai.opsmind.toolgateway.application.DelegatedCapabilityVerifier;
import ai.opsmind.toolgateway.application.DirectToolExecutionTransactionRunner;
import ai.opsmind.toolgateway.application.EvidenceNormalizer;
import ai.opsmind.toolgateway.application.ExecutionReceiptStore;
import ai.opsmind.toolgateway.application.FailClosedCapabilityVerifier;
import ai.opsmind.toolgateway.application.FailClosedExecutionReceiptStore;
import ai.opsmind.toolgateway.application.FailClosedNonceReplayStore;
import ai.opsmind.toolgateway.application.FixtureExecutionReceiptStore;
import ai.opsmind.toolgateway.application.FixtureNonceReplayStore;
import ai.opsmind.toolgateway.application.NonceReplayStore;
import ai.opsmind.toolgateway.application.PolicyEvaluator;
import ai.opsmind.toolgateway.application.RequestDigester;
import ai.opsmind.toolgateway.application.Rs256JwksDelegatedCapabilityVerifier;
import ai.opsmind.toolgateway.application.ToolExecutionService;
import ai.opsmind.toolgateway.application.ToolExecutionTransactionRunner;
import ai.opsmind.toolgateway.application.ToolManifestRegistry;
import ai.opsmind.toolgateway.application.ToolManifestResourceLoader;
import ai.opsmind.toolgateway.api.GatewayProblemWriter;
import ai.opsmind.toolgateway.api.GatewayReadiness;
import ai.opsmind.toolgateway.api.JsonRequestBodyLimitFilter;
import ai.opsmind.toolgateway.audit.DeterministicToolAuditWriter;
import ai.opsmind.toolgateway.audit.FailClosedToolAuditWriter;
import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.connectors.observability.FixtureObservabilityConnector;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class GatewayRuntimeConfiguration {

    @Bean
    JsonMapper gatewayJsonMapper() {
        return JsonMapper.builder()
            .findAndAddModules()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES
            )
            .build();
    }

    @Bean
    Clock gatewayClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("!persistence")
    NonceReplayStore nonceReplayStore(Environment environment, Clock gatewayClock) {
        if (environment.matchesProfiles("fixture")) return new FixtureNonceReplayStore(gatewayClock);
        return new FailClosedNonceReplayStore();
    }

    @Bean
    @Profile("!persistence")
    ExecutionReceiptStore executionReceiptStore(Environment environment) {
        if (environment.matchesProfiles("fixture")) return new FixtureExecutionReceiptStore();
        return new FailClosedExecutionReceiptStore();
    }

    @Bean
    DelegatedCapabilityVerifier delegatedCapabilityVerifier(
        GatewaySettings settings,
        NonceReplayStore nonceReplayStore,
        Clock gatewayClock,
        RequestDigester requestDigester
    ) {
        if (settings.jwkSetUri() == null) return new FailClosedCapabilityVerifier();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri().toString())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(BoundedJwksHttpClient.create())
            .build();
        return new Rs256JwksDelegatedCapabilityVerifier(
            decoder,
            nonceReplayStore,
            settings,
            gatewayClock,
            requestDigester
        );
    }

    @Bean
    @Profile("!prometheus")
    ToolManifestRegistry toolManifestRegistry(Environment environment, ObjectMapper objectMapper) {
        if (environment.matchesProfiles("fixture")) {
            return new ToolManifestResourceLoader(objectMapper).loadFixtureRegistry();
        }
        return new ToolManifestRegistry(List.of());
    }

    @Bean
    @Profile("fixture")
    ToolConnector fixtureObservabilityConnector() {
        return new FixtureObservabilityConnector();
    }

    @Bean
    PolicyEvaluator policyEvaluator(ObjectMapper objectMapper, GatewaySettings settings, Clock gatewayClock) {
        return new PolicyEvaluator(objectMapper, settings, gatewayClock);
    }

    @Bean
    EvidenceNormalizer evidenceNormalizer(ObjectMapper objectMapper, GatewaySettings settings) {
        return new EvidenceNormalizer(objectMapper, settings);
    }

    @Bean
    RequestDigester requestDigester(ObjectMapper objectMapper) {
        return new RequestDigester(objectMapper);
    }

    @Bean(destroyMethod = "close")
    BoundedConnectorExecutor boundedConnectorExecutor(Clock gatewayClock) {
        return new BoundedConnectorExecutor(gatewayClock);
    }

    @Bean
    @Profile("!persistence")
    ToolExecutionTransactionRunner toolExecutionTransactionRunner() {
        return new DirectToolExecutionTransactionRunner();
    }

    @Bean
    @Profile("!persistence")
    ToolAuditWriter toolAuditWriter(Environment environment) {
        if (environment.matchesProfiles("fixture")) return new DeterministicToolAuditWriter();
        return new FailClosedToolAuditWriter();
    }

    @Bean
    GatewayProblemWriter gatewayProblemWriter(ObjectMapper objectMapper) {
        return new GatewayProblemWriter(objectMapper);
    }

    @Bean
    JsonRequestBodyLimitFilter jsonRequestBodyLimitFilter(
        GatewayProblemWriter problemWriter,
        GatewaySettings settings
    ) {
        return new JsonRequestBodyLimitFilter(problemWriter, settings);
    }

    @Bean
    GatewayReadiness gatewayReadiness(
        GatewaySettings settings,
        NonceReplayStore nonceStore,
        ExecutionReceiptStore receiptStore,
        ToolAuditWriter auditWriter,
        ToolManifestRegistry manifests,
        List<ToolConnector> connectors
    ) {
        return new GatewayReadiness(
            settings, nonceStore, receiptStore, auditWriter, manifests, connectors
        );
    }

    @Bean
    ToolExecutionService toolExecutionService(
        DelegatedCapabilityVerifier capabilityVerifier,
        ToolManifestRegistry manifestRegistry,
        PolicyEvaluator policyEvaluator,
        ExecutionReceiptStore receiptStore,
        EvidenceNormalizer evidenceNormalizer,
        ToolAuditWriter auditWriter,
        RequestDigester requestDigester,
        BoundedConnectorExecutor connectorExecutor,
        ToolExecutionTransactionRunner transactionRunner,
        List<ToolConnector> connectors
    ) {
        return new ToolExecutionService(
            capabilityVerifier,
            manifestRegistry,
            policyEvaluator,
            receiptStore,
            evidenceNormalizer,
            auditWriter,
            requestDigester,
            connectorExecutor,
            transactionRunner,
            connectors
        );
    }
}
