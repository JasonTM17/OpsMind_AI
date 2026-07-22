package ai.opsmind.platform.analysis;

public interface AnalysisCapabilityTokenIssuer {

    String issue(AnalysisCapabilityGrant grant);
}
