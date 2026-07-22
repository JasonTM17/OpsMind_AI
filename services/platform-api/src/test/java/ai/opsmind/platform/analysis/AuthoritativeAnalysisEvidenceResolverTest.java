package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AuthoritativeAnalysisEvidenceResolverTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString(
        "11111111-1111-4111-8111-111111111111"
    );
    private static final UUID PROJECT_ID = UUID.fromString(
        "22222222-2222-4222-8222-222222222222"
    );
    private static final UUID INCIDENT_ID = UUID.fromString(
        "33333333-3333-4333-8333-333333333333"
    );
    private static final UUID ACTOR_ID = UUID.fromString(
        "44444444-4444-4444-8444-444444444444"
    );

    private final AuthoritativeAnalysisEvidenceResolver resolver =
        new AuthoritativeAnalysisEvidenceResolver(new ObjectMapper());

    @Test
    void redactsAuthorizedSnapshotBeforeDigestingProviderEvidence() {
        AuthorizedIncidentAnalysisEvidence authorized = new AuthorizedIncidentAnalysisEvidence(
            ORGANIZATION_ID,
            PROJECT_ID,
            INCIDENT_ID,
            ACTOR_ID,
            "API incident for operator@example.test",
            "password=never-send latency spike",
            IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING,
            null,
            null,
            3
        );

        ResolvedAnalysisEvidence evidence = resolver.resolve(
            authorized,
            "investigate",
            "incident_investigation"
        );

        assertThat(evidence.prompt()).contains("[REDACTED_EMAIL]", "[REDACTED_SECRET]");
        assertThat(evidence.prompt()).doesNotContain("operator@example.test", "never-send");
        assertThat(evidence.promptVersion()).isEqualTo("prompt-incident-authoritative-v1");
        assertThat(evidence.dataClassifications()).containsExactly("redacted_incident_summary");
        assertThat(evidence.contextRefs()).singleElement().satisfies(reference -> {
            assertThat(reference.evidenceId()).isEqualTo(INCIDENT_ID);
            assertThat(reference.sourceType()).isEqualTo("incident_summary");
            assertThat(reference.digest()).matches("sha256:[0-9a-f]{64}");
        });
    }

    @Test
    void redactsCompleteBearerJwtAndQuotedSecretsWithoutResidualCredentialText() {
        String jwt = "eyJ" + "hbGciOiJSUzI1NiJ9" + ".payload.signature";
        AuthorizedIncidentAnalysisEvidence authorized = new AuthorizedIncidentAnalysisEvidence(
            ORGANIZATION_ID,
            PROJECT_ID,
            INCIDENT_ID,
            ACTOR_ID,
            "{\"Authorization\": \"Bearer " + "opaque" + "-credential\"}",
            "Authorization: Bearer " + jwt
                + "\n{\"api" + "_key\": \"" + "opaque" + "-api-value\"}"
                + "\n{\"api" + "Key\": \"" + "camel" + "-api-value\"}"
                + "\n{\"xApi" + "Key\": \"" + "camel" + "-header-value\"}"
                + "\n{\"access" + "Token\": \"" + "camel" + "-access-value\"}"
                + "\n{\"bearer" + "Token\": \"" + "camel" + "-bearer-value\"}"
                + "\n{\"authorization" + "Header\": \"Bearer " + "camel" + "-auth-header-value\"}"
                + "\n{\"to" + "ken\": \"" + "camel" + "-token-value\"}"
                + "\n{\"api" + "Credential\": \"" + "camel" + "-credential-value\"}"
                + "\n{\"client" + "Secret\": \"" + "camel" + "-client-value\"}"
                + "\n{\"proxy" + "Authorization\": \"Bearer " + "camel" + "-proxy-value\"}"
                + "\n{\"set" + "Cookie\": \"" + "camel" + "-cookie-value\"}"
                + "\npassword=\"secret value with spaces\"",
            IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING,
            null,
            null,
            3
        );

        ResolvedAnalysisEvidence evidence = resolver.resolve(
            authorized,
            "investigate",
            "incident_investigation"
        );

        assertThat(evidence.prompt())
            .contains("[REDACTED_SECRET]")
            .doesNotContain(
                jwt,
                "Bearer",
                "opaque-credential",
                "opaque-api-value",
                "camel-",
                "secret value with spaces"
            );
    }

    @Test
    void rejectsUnrecognizedPurposeBeforePreparingEvidence() {
        assertThatThrownBy(() -> resolver.resolve(
            authorized(),
            "investigate",
            "caller-controlled-purpose"
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("analysis.evidence-scope-invalid")
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorized() {
        return new AuthorizedIncidentAnalysisEvidence(
            ORGANIZATION_ID,
            PROJECT_ID,
            INCIDENT_ID,
            ACTOR_ID,
            "API unavailable",
            "Redacted latency spike",
            IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING,
            null,
            null,
            1
        );
    }
}
