package ai.opsmind.platform.analysis;

import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;

/**
 * Resolves only a snapshot captured while tenant ACL authorization and the
 * authoritative incident read share one transaction. Implementations derive
 * classifications, redact the provider prompt, and digest the exact evidence.
 * No caller-declared evidence may be trusted.
 */
public interface AnalysisEvidenceResolver {

    ResolvedAnalysisEvidence resolve(
        AuthorizedIncidentAnalysisEvidence evidence,
        String analysisMode,
        String purpose
    );
}
