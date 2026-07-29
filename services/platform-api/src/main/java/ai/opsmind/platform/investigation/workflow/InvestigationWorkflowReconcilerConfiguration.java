package ai.opsmind.platform.investigation.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(InvestigationWorkflowReconcilerProperties.class)
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-reconciler",
    name = "enabled",
    havingValue = "true"
)
public class InvestigationWorkflowReconcilerConfiguration {

    @Bean
    @ConditionalOnMissingBean(InvestigationWorkflowStartEventCodec.class)
    InvestigationWorkflowStartEventCodec investigationWorkflowStartEventCodec(
        ObjectMapper objectMapper
    ) {
        return new InvestigationWorkflowStartEventCodec(objectMapper);
    }
}
