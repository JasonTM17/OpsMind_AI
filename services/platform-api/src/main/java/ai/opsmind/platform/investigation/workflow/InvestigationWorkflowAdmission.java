package ai.opsmind.platform.investigation.workflow;

/**
 * Fail-closed cutover boundary. Phase 9 supplies a Temporal-backed readiness
 * implementation before durable admission can be enabled.
 */
public interface InvestigationWorkflowAdmission {

    void requireReady(InvestigationWorkflowProperties properties);
}
