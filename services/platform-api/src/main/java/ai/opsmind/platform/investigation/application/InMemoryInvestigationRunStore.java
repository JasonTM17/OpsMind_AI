package ai.opsmind.platform.investigation.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repeatable local slice store used only by deterministic fixture tests. */
@Repository
@Profile("fixture")
@ConditionalOnProperty(
    prefix = "opsmind.investigation",
    name = "store",
    havingValue = "in-memory"
)
public final class InMemoryInvestigationRunStore implements InvestigationRunStore {

    private final Map<Key, InvestigationStateMachine.State> states = new ConcurrentHashMap<>();

    @Override
    public void create(InvestigationStateMachine.Step initial) {
        InvestigationStateMachine.State state = initial.state();
        if (states.putIfAbsent(new Key(state.organizationId(), state.runId()), state) != null) {
            throw new PlatformProblemException(
                HttpStatus.CONFLICT,
                "investigation.run-conflict",
                "The investigation run identifier is already in use."
            );
        }
    }

    @Override
    public void save(
        InvestigationStateMachine.State previous,
        InvestigationStateMachine.Step next
    ) {
        InvestigationStateMachine.State state = next.state();
        Key key = new Key(state.organizationId(), state.runId());
        if (!states.replace(key, previous, state)) {
            throw new PlatformProblemException(
                HttpStatus.CONFLICT,
                "investigation.run-missing",
                "The investigation run changed before it could be saved."
            );
        }
    }

    @Override
    public InvestigationStateMachine.State require(UUID organizationId, UUID actorId, UUID runId) {
        if (actorId == null) {
            throw new IllegalArgumentException("Actor identifier is required.");
        }
        InvestigationStateMachine.State state = states.get(new Key(organizationId, runId));
        if (state == null) {
            throw new PlatformProblemException(
                HttpStatus.NOT_FOUND, "investigation.run-not-found", "The investigation run was not found."
            );
        }
        return state;
    }

    private record Key(UUID organizationId, UUID runId) { }
}
