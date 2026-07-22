package ai.opsmind.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;

class PageTokenCodecTest {

    private final PageTokenCodec codec = new PageTokenCodec();

    @Test
    void roundTripsOpaqueVersionedToken() {
        UUID value = UUID.fromString("11111111-1111-4111-8111-111111111111");

        String token = codec.encode(value);

        assertThat(token).doesNotContain(value.toString());
        assertThat(codec.decode(token)).isEqualTo(value);
    }

    @Test
    void rejectsMalformedOrOversizedTokens() {
        assertThatThrownBy(() -> codec.decode("not-a-token"))
            .isInstanceOf(PlatformProblemException.class);
        assertThatThrownBy(() -> codec.decode("x".repeat(513)))
            .isInstanceOf(PlatformProblemException.class);
    }
}
