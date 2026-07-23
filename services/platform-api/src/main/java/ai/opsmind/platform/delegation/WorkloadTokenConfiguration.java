package ai.opsmind.platform.delegation;

import java.net.http.HttpClient;
import java.time.Clock;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "opsmind.tool-gateway.workload",
    name = "enabled",
    havingValue = "true"
)
class WorkloadTokenConfiguration {

    @Bean
    WorkloadTokenProvider workloadTokenProvider(
        OAuthClientCredentialsProperties properties,
        ObjectMapper objectMapper
    ) {
        properties.validateEnabled();
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return new OAuthClientCredentialsWorkloadTokenProvider(
            properties, httpClient, Clock.systemUTC(), objectMapper
        );
    }
}
