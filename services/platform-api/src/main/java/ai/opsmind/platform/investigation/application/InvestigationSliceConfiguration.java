package ai.opsmind.platform.investigation.application;

import java.time.Clock;

import ai.opsmind.platform.investigation.integration.FixtureInvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationToolGatewayClient;
import ai.opsmind.platform.investigation.integration.InvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolGatewayClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
