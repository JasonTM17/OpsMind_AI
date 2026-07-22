package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class IncidentRequestIdentityTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTOR_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTOR_B = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void createDigestBindsActorAndNormalizesEquivalentText() {
        CreateIncidentRequest canonical = new CreateIncidentRequest(
            "API unavailable", "5xx spike", IncidentSeverity.SEV1, "alert"
        );
        CreateIncidentRequest padded = new CreateIncidentRequest(
            "  API unavailable  ", "  5xx spike ", IncidentSeverity.SEV1, " alert "
        );

        byte[] actorA = IncidentRequestIdentity.create(
            ACTOR_A, ORGANIZATION_ID, PROJECT_ID, canonical
        );
        byte[] actorB = IncidentRequestIdentity.create(
            ACTOR_B, ORGANIZATION_ID, PROJECT_ID, canonical
        );

        assertThat(actorA).isEqualTo(IncidentRequestIdentity.create(
            ACTOR_A, ORGANIZATION_ID, PROJECT_ID, padded
        ));
        assertThat(actorA).isNotEqualTo(actorB);
    }

    @Test
    void transitionDigestBindsPathVersionAndBody() {
        UUID incidentId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        TransitionIncidentRequest request = new TransitionIncidentRequest(
            IncidentStatus.INVESTIGATING, "triage", null, null
        );
        byte[] baseline = IncidentRequestIdentity.transition(
            ACTOR_A, ORGANIZATION_ID, PROJECT_ID, incidentId, 0, request
        );

        assertThat(baseline).isNotEqualTo(IncidentRequestIdentity.transition(
            ACTOR_A, ORGANIZATION_ID, PROJECT_ID, incidentId, 1, request
        ));
        assertThat(baseline).isNotEqualTo(IncidentRequestIdentity.transition(
            ACTOR_A, ORGANIZATION_ID, PROJECT_ID, UUID.randomUUID(), 0, request
        ));
    }
}
