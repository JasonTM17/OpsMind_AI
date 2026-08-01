package ai.opsmind.platform.investigation.workflow;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InvestigationWorkflowReconciliationMetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(InvestigationWorkflowReconciliationMetrics.class)
    InvestigationWorkflowReconciliationMetrics investigationWorkflowReconciliationMetrics(
        MeterRegistry registry
    ) {
        return new InvestigationWorkflowReconciliationMetrics(registry);
    }
}
