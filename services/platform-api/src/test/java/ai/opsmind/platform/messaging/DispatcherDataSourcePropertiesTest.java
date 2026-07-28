package ai.opsmind.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DispatcherDataSourcePropertiesTest {

    @Test
    void exactDispatcherLoginIsRequiredAndPasswordNeverRenders() {
        DispatcherDataSourceProperties properties = new DispatcherDataSourceProperties();
        properties.setUrl("jdbc:postgresql://db.example.test:5432/opsmind");
        properties.setUsername("opsmind_dispatcher");
        properties.setPassword("do-not-render");

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.toString())
            .contains("opsmind_dispatcher", "password=<redacted>")
            .doesNotContain("do-not-render");
    }

    @Test
    void appLoginCannotBeReusedAsDispatcher() {
        DispatcherDataSourceProperties properties = new DispatcherDataSourceProperties();
        properties.setUrl("jdbc:postgresql://db.example.test:5432/opsmind");
        properties.setUsername("opsmind_app");
        properties.setPassword("synthetic");

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside policy");
    }
}
