package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;

import org.springframework.http.HttpStatus;

final class InvestigationStartDeadlinePolicy {

    private InvestigationStartDeadlinePolicy() { }

    static void requireActive(InvestigationCommand.Start command) {
        if (command.deadlineAt().isAfter(command.startedAt())) {
            return;
        }
        throw new PlatformProblemException(
            HttpStatus.REQUEST_TIMEOUT,
            "investigation.deadline-exceeded",
            "The investigation deadline elapsed."
        );
    }
}
