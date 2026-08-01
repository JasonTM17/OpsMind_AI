package ai.opsmind.platform.evidence.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class EvidenceArtifactStoragePropertiesTest {

    @Test
    void acceptsDefaultDisabledValuesWithoutStorageDetails() {
        EvidenceArtifactStorageProperties properties = new EvidenceArtifactStorageProperties(
            false, null, false, null, null, false, null, null, null, null,
            0, null, null, null, null, null, null, 0, null
        );

        assertThatCode(properties::validateForEnablement).doesNotThrowAnyException();
        assertThat(properties.maximumObjectBytes())
            .isEqualTo(EvidenceArtifactStorageProperties.MAXIMUM_SUPPORTED_OBJECT_BYTES);
        assertThat(properties.toString()).isEqualTo("EvidenceArtifactStorageProperties[enabled=false]");
    }

    @Test
    void permitsCleartextOnlyForExplicitLiteralLoopbackMode() {
        EvidenceArtifactStorageProperties properties = enabled(URI.create("http://127.0.0.1:9000"), true);

        assertThatCode(properties::validateForEnablement).doesNotThrowAnyException();
    }

    @Test
    void rejectsCleartextNonLoopbackAndHostnameLoopback() {
        assertThatThrownBy(() -> enabled(URI.create("http://storage.example.com"), true)
            .validateForEnablement()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> enabled(URI.create("http://localhost:9000"), true)
            .validateForEnablement()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTimeoutsThatCanOutliveTheUploadLease() {
        EvidenceArtifactStorageProperties properties = new EvidenceArtifactStorageProperties(
            true, URI.create("https://storage.example.com"), false, "ap-southeast-1",
            "evidence-artifacts", true, "123456789012",
            "arn:aws:kms:ap-southeast-1:123456789012:key/key-1",
            "arn:aws:kms:ap-southeast-1:123456789012:key/key-1", "production-kms",
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(20),
            Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5),
            4, Duration.ofMinutes(1)
        );

        assertThatThrownBy(properties::validateForEnablement).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsLeaseAboveTheDatabaseFence() {
        EvidenceArtifactStorageProperties properties = new EvidenceArtifactStorageProperties(
            true, URI.create("https://storage.example.com"), false, "ap-southeast-1",
            "evidence-artifacts", true, "123456789012",
            "arn:aws:kms:ap-southeast-1:123456789012:key/key-1",
            "arn:aws:kms:ap-southeast-1:123456789012:key/key-1", "production-kms",
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5),
            4, Duration.ofMinutes(6)
        );

        assertThatThrownBy(properties::validateForEnablement).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresStrictRoomForSourceVerificationAndSettlement() {
        EvidenceArtifactStorageProperties exactLeaseBudget = enabledWithBudgets(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofSeconds(45)
        );
        EvidenceArtifactStorageProperties leaseWithRoom = enabledWithBudgets(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofSeconds(46)
        );

        assertThatThrownBy(exactLeaseBudget::validateForEnablement)
            .isInstanceOf(IllegalStateException.class);
        assertThatCode(leaseWithRoom::validateForEnablement).doesNotThrowAnyException();
    }

    private static EvidenceArtifactStorageProperties enabled(URI endpoint, boolean allowLoopbackCleartext) {
        return new EvidenceArtifactStorageProperties(
            true, endpoint, allowLoopbackCleartext, "ap-southeast-1", "evidence-artifacts",
            true, "123456789012", "arn:aws:kms:ap-southeast-1:123456789012:key/key-1",
            "arn:aws:kms:ap-southeast-1:123456789012:key/key-1", "production-kms",
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            Duration.ofSeconds(5), 4, Duration.ofMinutes(1)
        );
    }

    private static EvidenceArtifactStorageProperties enabledWithBudgets(
        Duration apiCallTimeout,
        Duration sourceVerificationBudget,
        Duration settlementSafetyMargin,
        Duration uploadLeaseDuration
    ) {
        return new EvidenceArtifactStorageProperties(
            true, URI.create("https://storage.example.com"), false, "ap-southeast-1",
            "evidence-artifacts", true, "123456789012",
            "arn:aws:kms:ap-southeast-1:123456789012:key/key-1",
            "arn:aws:kms:ap-southeast-1:123456789012:key/key-1", "production-kms",
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(5), apiCallTimeout, sourceVerificationBudget,
            settlementSafetyMargin, 4, uploadLeaseDuration
        );
    }
}
