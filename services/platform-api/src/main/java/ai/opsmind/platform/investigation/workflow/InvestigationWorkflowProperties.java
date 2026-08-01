package ai.opsmind.platform.investigation.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "opsmind.investigation.workflow")
public record InvestigationWorkflowProperties(
    String clusterId,
    String namespace,
    String workflowType,
    String taskQueue
) {
    private static final String DISABLED = "disabled";
    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

    @ConstructorBinding
    public InvestigationWorkflowProperties {
        clusterId = defaultValue(clusterId, DISABLED);
        namespace = defaultValue(namespace, DISABLED);
        workflowType = defaultValue(workflowType, "opsmind-investigation-v1");
        taskQueue = defaultValue(taskQueue, DISABLED);
    }

    public void validateStartTarget() {
        requireName(clusterId, 64, "Temporal logical cluster identifier");
        requireName(namespace, 255, "Temporal namespace");
        requireName(workflowType, 128, "Temporal workflow type");
        requireName(taskQueue, 255, "Temporal task queue");
        if (isDisabledTarget()) {
            throw new IllegalStateException("Temporal workflow target is disabled.");
        }
    }

    private boolean isDisabledTarget() {
        return DISABLED.equals(clusterId) || DISABLED.equals(namespace) || DISABLED.equals(taskQueue);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static void requireName(String value, int maximumLength, String field) {
        if (value == null || value.length() > maximumLength || !value.matches(NAME_PATTERN)) {
            throw new IllegalStateException(field + " is outside policy.");
        }
    }
}
