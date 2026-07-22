package ai.opsmind.platform.messaging;

public interface OutboxRepository {

    void append(EventEnvelope event);
}
