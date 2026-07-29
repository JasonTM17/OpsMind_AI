package ai.opsmind.toolgateway.application;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.opsmind.toolgateway.config.ConnectorBulkheadProperties;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;

/**
 * Fail-fast connector admission with one process ceiling and one ceiling shared
 * by every project belonging to the same verified tenant.
 */
final class TenantConnectorBulkhead {

    private final Semaphore globalCapacity;
    private final int perTenantConcurrency;
    private final ConcurrentMap<UUID, TenantSlot> tenantSlots = new ConcurrentHashMap<>();

    TenantConnectorBulkhead(ConnectorBulkheadProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Connector bulkhead properties are required.");
        }
        this.globalCapacity = new Semaphore(properties.globalConcurrency(), true);
        this.perTenantConcurrency = properties.perTenantConcurrency();
    }

    Permit acquire(TenantProjectScope trustedScope) {
        if (trustedScope == null) {
            throw new ToolDeniedException(
                DenialCode.CAPABILITY_SCOPE_MISMATCH,
                "Verified capability scope is unavailable."
            );
        }

        UUID tenantId = trustedScope.tenantId();
        TenantSlot tenantSlot = retain(tenantId);
        if (!tenantSlot.capacity.tryAcquire()) {
            releaseReference(tenantId, tenantSlot);
            throw exhausted();
        }
        if (!globalCapacity.tryAcquire()) {
            tenantSlot.capacity.release();
            releaseReference(tenantId, tenantSlot);
            throw exhausted();
        }
        return new Permit(this, tenantId, tenantSlot);
    }

    int trackedTenantCount() {
        return tenantSlots.size();
    }

    private TenantSlot retain(UUID tenantId) {
        return tenantSlots.compute(tenantId, (ignored, existing) -> {
            TenantSlot slot = existing == null
                ? new TenantSlot(perTenantConcurrency) : existing;
            slot.references++;
            return slot;
        });
    }

    private void release(UUID tenantId, TenantSlot tenantSlot) {
        globalCapacity.release();
        tenantSlot.capacity.release();
        releaseReference(tenantId, tenantSlot);
    }

    private void releaseReference(UUID tenantId, TenantSlot tenantSlot) {
        tenantSlots.compute(tenantId, (ignored, current) -> {
            if (current != tenantSlot) {
                throw new IllegalStateException("Tenant connector slot identity changed.");
            }
            current.references--;
            if (current.references < 0) {
                throw new IllegalStateException("Tenant connector slot reference underflow.");
            }
            return current.references == 0 ? null : current;
        });
    }

    private ToolDeniedException exhausted() {
        return new ToolDeniedException(
            DenialCode.EXECUTION_BACKPRESSURE,
            "Tool connector capacity is exhausted."
        );
    }

    static final class Permit implements AutoCloseable {

        private final TenantConnectorBulkhead owner;
        private final UUID tenantId;
        private final TenantSlot tenantSlot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(
            TenantConnectorBulkhead owner,
            UUID tenantId,
            TenantSlot tenantSlot
        ) {
            this.owner = owner;
            this.tenantId = tenantId;
            this.tenantSlot = tenantSlot;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(tenantId, tenantSlot);
            }
        }
    }

    private static final class TenantSlot {

        private final Semaphore capacity;
        private int references;

        private TenantSlot(int capacity) {
            this.capacity = new Semaphore(capacity, true);
        }
    }
}
