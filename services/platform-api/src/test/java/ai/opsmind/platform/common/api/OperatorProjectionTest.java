package ai.opsmind.platform.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OperatorProjectionTest {

    @Test
    void requiresAnExactPositiveQualityVendorMediaType() throws Exception {
        assertThat(OperatorProjection.requested(null)).isFalse();
        assertThat(OperatorProjection.requested("*/*")).isFalse();
        assertThat(OperatorProjection.requested("application/json")).isFalse();
        assertThatThrownBy(() -> OperatorProjection.requested(
            OperatorProjection.MEDIA_TYPE_VALUE + ";q=0"
        )).isInstanceOf(org.springframework.web.HttpMediaTypeNotAcceptableException.class);
        assertThat(OperatorProjection.requested(
            "application/json;q=0.2, " + OperatorProjection.MEDIA_TYPE_VALUE + ";q=1"
        )).isTrue();
        assertThat(OperatorProjection.requested(
            "application/json;q=1, " + OperatorProjection.MEDIA_TYPE_VALUE + ";q=0.2"
        )).isFalse();
        assertThatThrownBy(() -> OperatorProjection.requested(
            OperatorProjection.MEDIA_TYPE_VALUE + ";profile=unreviewed"
        )).isInstanceOf(org.springframework.web.HttpMediaTypeNotAcceptableException.class);
    }
}
