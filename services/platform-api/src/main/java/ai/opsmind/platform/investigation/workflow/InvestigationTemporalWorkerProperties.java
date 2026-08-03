package ai.opsmind.platform.investigation.workflow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "opsmind.investigation.temporal-worker")
public record InvestigationTemporalWorkerProperties(
    boolean enabled,
    String identity,
    String buildId,
    Integer maxConcurrentWorkflowTaskExecutors,
    Integer maxConcurrentWorkflowTaskPollers,
    Duration shutdownTimeout
) {
    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

    @ConstructorBinding
    public InvestigationTemporalWorkerProperties {
        identity = defaultValue(identity);
        buildId = defaultValue(buildId);
        maxConcurrentWorkflowTaskExecutors = maxConcurrentWorkflowTaskExecutors == null
            ? 32 : maxConcurrentWorkflowTaskExecutors;
        maxConcurrentWorkflowTaskPollers = maxConcurrentWorkflowTaskPollers == null
            ? 5 : maxConcurrentWorkflowTaskPollers;
        shutdownTimeout = shutdownTimeout == null
            ? Duration.ofSeconds(10) : shutdownTimeout;
    }

    public void validate(
        InvestigationTemporalClientProperties client,
        InvestigationWorkflowProperties workflow
    ) {
        client.validate(workflow);
        if (!enabled
            || !InvestigationWorkflow.TYPE.equals(workflow.workflowType())
            || !validName(identity, 160)
            || !validName(buildId, 128)
            || !identity.equals(client.requiredWorkerIdentity())
            || !buildId.equals(client.requiredWorkerBuildId())
            || maxConcurrentWorkflowTaskExecutors < 1
            || maxConcurrentWorkflowTaskExecutors > 128
            || maxConcurrentWorkflowTaskPollers < 1
            || maxConcurrentWorkflowTaskPollers > 20
            || maxConcurrentWorkflowTaskPollers > maxConcurrentWorkflowTaskExecutors
            || shutdownTimeout.compareTo(Duration.ofSeconds(1)) < 0
            || shutdownTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("Temporal worker configuration is outside policy.");
        }
    }

    private static boolean validName(String value, int maximumLength) {
        return value.length() <= maximumLength
            && value.matches(NAME_PATTERN)
            && !"disabled".equals(value);
    }

    private static String defaultValue(String value) {
        return value == null || value.isBlank() ? "disabled" : value.strip();
    }
}
