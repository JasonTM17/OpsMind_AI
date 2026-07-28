package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

public interface InvestigationExecutionStarter {

    StartResult start(InvestigationCommand.Start command, InvestigationExecutionContext context);

    record StartResult(InvestigationStateMachine.State state, boolean asynchronous) {
        public StartResult {
            if (state == null) {
                throw new IllegalArgumentException("Investigation start state is required.");
            }
        }
    }
}
