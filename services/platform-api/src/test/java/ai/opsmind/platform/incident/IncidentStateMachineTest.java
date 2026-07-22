package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IncidentStateMachineTest {

    private static final Map<IncidentStatus, Set<IncidentStatus>> LEGAL = Map.of(
        IncidentStatus.OPEN, Set.of(IncidentStatus.INVESTIGATING),
        IncidentStatus.INVESTIGATING,
            Set.of(IncidentStatus.AWAITING_APPROVAL, IncidentStatus.MITIGATING, IncidentStatus.RESOLVED),
        IncidentStatus.AWAITING_APPROVAL,
            Set.of(IncidentStatus.INVESTIGATING, IncidentStatus.MITIGATING),
        IncidentStatus.MITIGATING,
            Set.of(IncidentStatus.INVESTIGATING, IncidentStatus.RESOLVED),
        IncidentStatus.RESOLVED, Set.of(IncidentStatus.INVESTIGATING, IncidentStatus.CLOSED),
        IncidentStatus.CLOSED, Set.of()
    );

    @Test
    void enforcesCompleteTransitionMatrix() {
        for (IncidentStatus current : IncidentStatus.values()) {
            for (IncidentStatus target : IncidentStatus.values()) {
                boolean expected = LEGAL.get(current).contains(target);
                assertThat(IncidentStateMachine.allows(current, target))
                    .as("%s -> %s", current, target)
                    .isEqualTo(expected);
                if (!expected) {
                    assertThatThrownBy(() -> apply(current, target))
                        .as("%s -> %s", current, target)
                        .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                            assertThat(exception.code()).isEqualTo("incident.transition-not-allowed"));
                }
            }
        }
    }

    @Test
    void resolutionIsRequiredOnlyForResolvedAndReopenClearsCurrentValues() {
        var resolved = IncidentStateMachine.apply(
            IncidentStatus.MITIGATING,
            IncidentStatus.RESOLVED,
            null,
            null,
            "  database lock contention  ",
            "  reduced transaction scope  "
        );
        assertThat(resolved.rootCause()).isEqualTo("database lock contention");
        assertThat(resolved.resolutionSummary()).isEqualTo("reduced transaction scope");

        var reopened = IncidentStateMachine.apply(
            IncidentStatus.RESOLVED,
            IncidentStatus.INVESTIGATING,
            resolved.rootCause(),
            resolved.resolutionSummary(),
            null,
            null
        );
        assertThat(reopened.rootCause()).isNull();
        assertThat(reopened.resolutionSummary()).isNull();

        assertThatThrownBy(() -> IncidentStateMachine.apply(
            IncidentStatus.MITIGATING,
            IncidentStatus.RESOLVED,
            null,
            null,
            "root",
            null
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            {
                assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(exception.code()).isEqualTo("incident.resolution-required");
            });
        assertThatThrownBy(() -> IncidentStateMachine.apply(
            IncidentStatus.OPEN,
            IncidentStatus.INVESTIGATING,
            null,
            null,
            "not applicable",
            null
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            {
                assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(exception.code()).isEqualTo("incident.resolution-not-applicable");
            });
    }

    private IncidentStateMachine.ResolutionFields apply(
        IncidentStatus current,
        IncidentStatus target
    ) {
        String existingRoot = current == IncidentStatus.RESOLVED ? "root" : null;
        String existingSummary = current == IncidentStatus.RESOLVED ? "resolution" : null;
        String requestedRoot = target == IncidentStatus.RESOLVED ? "root" : null;
        String requestedSummary = target == IncidentStatus.RESOLVED ? "resolution" : null;
        return IncidentStateMachine.apply(
            current,
            target,
            existingRoot,
            existingSummary,
            requestedRoot,
            requestedSummary
        );
    }
}
