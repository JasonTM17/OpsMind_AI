package ai.opsmind.platform.investigation.workflow;

import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    InvestigationTemporalObserverProperties.class,
    InvestigationWorkflowReconcilerProperties.class
})
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-reconciler",
    name = "enabled",
    havingValue = "true"
)
@ConditionalOnProperty(
    prefix = "opsmind.investigation.temporal-observer",
    name = "enabled",
    havingValue = "true"
)
public class InvestigationTemporalObserverConfiguration {

    @Bean(
        name = "investigationWorkflowObserverServiceStubs",
        destroyMethod = "shutdown",
        defaultCandidate = false
    )
    WorkflowServiceStubs investigationWorkflowObserverServiceStubs(
        InvestigationTemporalObserverProperties observerProperties,
        InvestigationWorkflowProperties workflowProperties,
        InvestigationWorkflowReconcilerProperties reconcilerProperties
    ) {
        observerProperties.validate(workflowProperties);
        reconcilerProperties.validate(observerProperties.getRpcTimeout());
        var options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(observerProperties.getTarget())
            .setEnableHttps(observerProperties.isTlsEnabled())
            .setRpcTimeout(observerProperties.getRpcTimeout());
        if (observerProperties.isTlsEnabled()) {
            options.addApiKey(observerProperties::getApiKey);
        }
        return WorkflowServiceStubs.newServiceStubs(options.build());
    }

    @Bean
    InvestigationWorkflowReconciliationMetrics investigationWorkflowReconciliationMetrics(
        MeterRegistry registry
    ) {
        return new InvestigationWorkflowReconciliationMetrics(registry);
    }

    @Bean
    InvestigationWorkflowObserver investigationWorkflowObserver(
        @Qualifier("investigationWorkflowObserverServiceStubs")
        WorkflowServiceStubs serviceStubs,
        InvestigationTemporalObserverProperties observerProperties,
        InvestigationWorkflowProperties workflowProperties,
        InvestigationWorkflowReconciliationMetrics metrics
    ) {
        InvestigationWorkflowObserver observer =
            new TemporalInvestigationWorkflowObserver(
                serviceStubs,
                observerProperties,
                workflowProperties,
                DefaultDataConverter.STANDARD_INSTANCE,
                metrics
            );
        metrics.updateObserverReady(true);
        return observer;
    }
}
