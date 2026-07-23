package ai.opsmind.platform.investigation.application;

import java.time.Clock;

import ai.opsmind.platform.analysis.AnalysisCapabilityTokenIssuer;
import ai.opsmind.platform.analysis.AnalysisCapabilityProperties;
import ai.opsmind.platform.analysis.AnalysisEvidenceResolver;
import ai.opsmind.platform.analysis.AnalysisRequestCanonicalizer;
import ai.opsmind.platform.analysis.AnalysisRuntimeClient;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.investigation.integration.AuthorizedInvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationToolGatewayClient;
import ai.opsmind.platform.investigation.integration.InvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolGatewayClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolIntentCatalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "enabled", havingValue = "true")
public class InvestigationSliceConfiguration {

    @Bean
    Clock investigationClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("fixture")
    @ConditionalOnProperty(prefix = "opsmind.investigation", name = "fixture", havingValue = "true")
    InvestigationAiRuntimeClient fixtureInvestigationAiRuntimeClient() {
        return new FixtureInvestigationAiRuntimeClient();
    }

    @Bean
    @Profile("!fixture")
    InvestigationAiRuntimeClient authorizedInvestigationAiRuntimeClient(
        IncidentAnalysisAuthorizer authorizer,
        AnalysisEvidenceResolver evidenceResolver,
        AnalysisRequestCanonicalizer canonicalizer,
        AnalysisCapabilityTokenIssuer tokenIssuer,
        AnalysisRuntimeClient runtimeClient,
        InvestigationToolIntentCatalog catalog,
        EvidenceContentCanonicalizer evidenceCanonicalizer,
        ObjectMapper objectMapper,
        AnalysisCapabilityProperties capabilityProperties,
        Clock investigationClock
    ) {
        return new AuthorizedInvestigationAiRuntimeClient(
            authorizer, evidenceResolver, canonicalizer, tokenIssuer, runtimeClient, catalog,
            evidenceCanonicalizer, objectMapper, capabilityProperties.maximumLifetime(),
            investigationClock
        );
    }

    @Bean
    @Profile("fixture")
    @ConditionalOnProperty(prefix = "opsmind.investigation", name = "fixture", havingValue = "true")
    InvestigationToolGatewayClient fixtureInvestigationToolGatewayClient() {
        return new FixtureInvestigationToolGatewayClient();
    }

    @Bean
    InvestigationOrchestrator investigationOrchestrator(
        InvestigationRunStore store,
        InvestigationAiRuntimeClient aiRuntime,
        InvestigationToolGatewayClient toolGateway,
        Clock investigationClock
    ) {
        return new InvestigationOrchestrator(store, aiRuntime, toolGateway, investigationClock);
    }
}
