package ai.opsmind.platform.messaging;

import java.util.UUID;

public interface InboxRepository {

    boolean claim(UUID organizationId, UUID eventId, String consumer);

    boolean markProcessed(UUID organizationId, UUID eventId, String consumer);

    boolean markPoisoned(
        UUID organizationId,
        UUID eventId,
        String consumer,
        String errorCode
    );
}
