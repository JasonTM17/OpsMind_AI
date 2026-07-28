package ai.opsmind.platform.messaging;

public record DispatcherDatabaseIdentity(String sessionUser, String currentUser) {

    public DispatcherDatabaseIdentity {
        if (!"opsmind_dispatcher".equals(sessionUser)
            || !"opsmind_dispatcher".equals(currentUser)) {
            throw new IllegalStateException("Dispatcher database identity is not isolated.");
        }
    }
}
