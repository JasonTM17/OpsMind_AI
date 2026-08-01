package ai.opsmind.platform.investigation.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    InvestigationTemporalClientProperties.class,
    InvestigationWorkflowProperties.class,
    InvestigationTemporalWorkerProperties.class
})
@ConditionalOnProperty(
    prefix = "opsmind.investigation.temporal-worker",
    name = "enabled",
    havingValue = "true"
)
public class InvestigationTemporalWorkerConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(InvestigationTemporalWorkerRuntime.class)
    InvestigationTemporalWorkerRuntime investigationTemporalWorkerRuntime(
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties,
        InvestigationTemporalWorkerProperties workerProperties
    ) {
        return new InvestigationTemporalWorkerRuntime(
            clientProperties, workflowProperties, workerProperties
        );
    }
}
