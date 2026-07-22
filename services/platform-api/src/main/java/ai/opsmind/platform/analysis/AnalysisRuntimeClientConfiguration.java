package ai.opsmind.platform.analysis;

import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "opsmind.ai-runtime.client",
    name = "enabled",
    havingValue = "true"
)
class AnalysisRuntimeClientConfiguration {

    @Bean
    AnalysisRuntimeClient analysisRuntimeClient(
        AnalysisRuntimeClientProperties properties,
        ObjectMapper objectMapper
    ) {
        properties.validateEnabled();
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return new HttpAnalysisRuntimeClient(properties, client, objectMapper);
    }
}
