package ai.opsmind.platform.messaging;

public record WorkflowReconcilerDatabaseIdentity(String sessionUser, String currentUser) {

    public WorkflowReconcilerDatabaseIdentity {
        if (!"opsmind_workflow_reconciler".equals(sessionUser)
            || !"opsmind_workflow_reconciler".equals(currentUser)) {
            throw new IllegalStateException(
                "Workflow reconciler database identity is not isolated."
            );
        }
    }
}
