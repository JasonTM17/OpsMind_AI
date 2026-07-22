package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.ObjectMapper;

class AnalysisEvidenceResolverConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(AuthoritativeAnalysisEvidenceResolver.class);

    @Test
    void enabledRuntimePublishesAuthoritativeResolverBean() {
        context
            .withPropertyValues("opsmind.ai-runtime.client.enabled=true")
            .run(application -> {
                assertThat(application).hasNotFailed();
                assertThat(application).hasSingleBean(AnalysisEvidenceResolver.class);
                assertThat(application.getBean(AnalysisEvidenceResolver.class))
                    .isInstanceOf(AuthoritativeAnalysisEvidenceResolver.class);
            });
    }

    @Test
    void disabledRuntimeDoesNotPublishResolverBean() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(AnalysisEvidenceResolver.class);
        });
    }
}
