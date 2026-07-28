package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

/**
 * Package-scoped composition boundary for the caller-owned handoff transaction.
 */
interface InvestigationInitialRunWriter {

    boolean createIfAbsentInCurrentTransaction(InvestigationStateMachine.Step initial);
}
