package ai.opsmind.platform.audit;

public interface AuditRepository {

    void append(AuditEvent event);
}
