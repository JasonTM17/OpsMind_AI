package ai.opsmind.platform.delegation;

public interface ToolCapabilityTokenIssuer {

    String issue(ToolCapabilityGrant grant);
}
