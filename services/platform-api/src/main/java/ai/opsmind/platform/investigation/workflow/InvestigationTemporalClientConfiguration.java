package ai.opsmind.platform.investigation.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvestigationTemporalClientProperties.class)
@ConditionalOnProperty(
    prefix = "opsmind.investigation.temporal-client",
    name = "enabled",
    havingValue = "true"
)
public class InvestigationTemporalClientConfiguration {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs investigationWorkflowServiceStubs(
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties
    ) {
        clientProperties.validate(workflowProperties);
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(clientProperties.target())
            .setEnableHttps(clientProperties.tlsEnabled())
            .setRpcTimeout(clientProperties.rpcTimeout())
            .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    WorkflowClient investigationTemporalWorkflowClient(
        WorkflowServiceStubs serviceStubs,
        InvestigationWorkflowProperties workflowProperties
    ) {
        return WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(workflowProperties.namespace())
                .setIdentity("opsmind-investigation-workflow-starter")
                .build()
        );
    }

    @Bean
    InvestigationWorkflowClient investigationWorkflowClient(
        WorkflowClient workflowClient,
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkflowProperties workflowProperties
    ) {
        return new TemporalInvestigationWorkflowClient(
            workflowClient, clientProperties, workflowProperties
        );
    }

    @Bean
    InvestigationWorkerReadinessProbe investigationWorkerReadinessProbe(
        WorkflowServiceStubs serviceStubs
    ) {
        return new TemporalInvestigationWorkerReadinessProbe(serviceStubs);
    }

    @Bean
    InvestigationWorkflowAdmission investigationWorkflowAdmission(
        InvestigationTemporalClientProperties clientProperties,
        InvestigationWorkerReadinessProbe readinessProbe
    ) {
        return new TemporalInvestigationWorkflowAdmission(clientProperties, readinessProbe);
    }
}
