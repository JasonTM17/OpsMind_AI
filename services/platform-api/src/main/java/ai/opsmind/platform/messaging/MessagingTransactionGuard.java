package ai.opsmind.platform.messaging;

import org.springframework.transaction.support.TransactionSynchronizationManager;

final class MessagingTransactionGuard {

    private MessagingTransactionGuard() {
    }

    static void requireActive() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox/inbox operations require an active database transaction.");
        }
    }
}
