package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowAdmission;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowHandoffRepository;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowProperties;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowStarterProperties;
import ai.opsmind.platform.investigation.workflow.InvestigationTemporalClientProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    InvestigationWorkflowProperties.class,
    InvestigationWorkflowStarterProperties.class,
    InvestigationTemporalClientProperties.class
})
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "enabled", havingValue = "true")
public class InvestigationExecutionConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "opsmind.investigation",
        name = "execution-mode",
        havingValue = "inline",
        matchIfMissing = true
    )
    InvestigationExecutionStarter inlineInvestigationExecutionStarter(
        InvestigationOrchestrator orchestrator
    ) {
        return new InlineInvestigationExecutionStarter(orchestrator);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "opsmind.investigation",
        name = "execution-mode",
        havingValue = "temporal"
    )
    InvestigationExecutionStarter durableInvestigationExecutionStarter(
        InvestigationWorkflowAdmission admission,
        InvestigationWorkflowHandoffRepository handoffRepository,
        InvestigationWorkflowProperties properties,
        InvestigationWorkflowStarterProperties starterProperties,
        InvestigationTemporalClientProperties clientProperties
    ) {
        if (!starterProperties.enabled()) {
            throw new IllegalStateException(
                "Temporal investigation execution requires the workflow starter."
            );
        }
        starterProperties.validateRpcEnvelope(clientProperties.rpcTimeout());
        return new DurableInvestigationExecutionStarter(admission, handoffRepository, properties);
    }
}
