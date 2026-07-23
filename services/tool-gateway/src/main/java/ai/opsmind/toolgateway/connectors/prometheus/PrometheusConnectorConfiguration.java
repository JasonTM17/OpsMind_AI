package ai.opsmind.toolgateway.connectors.prometheus;

import java.net.http.HttpClient;
import java.time.Clock;

import ai.opsmind.toolgateway.application.ToolManifestRegistry;
import ai.opsmind.toolgateway.application.ToolManifestResourceLoader;
import ai.opsmind.toolgateway.connectors.ToolConnector;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prometheus")
@ConditionalOnProperty(
    prefix = "opsmind.tool-gateway.prometheus",
    name = "enabled",
    havingValue = "true"
)
@EnableConfigurationProperties(PrometheusConnectorProperties.class)
public class PrometheusConnectorConfiguration {

    @Bean
    ToolManifestRegistry toolManifestRegistry(
        ObjectMapper objectMapper,
        PrometheusConnectorProperties properties
    ) {
        return new ToolManifestResourceLoader(objectMapper)
            .loadPrometheusRegistry(properties.egressTarget());
    }

    @Bean
    ToolConnector prometheusObservabilityConnector(
        ObjectMapper objectMapper,
        PrometheusConnectorProperties properties,
        Clock gatewayClock
    ) {
        HttpClient client = PrometheusHttpClientFactory.create(properties);
        PrometheusHttpExchange exchange = new PrometheusHttpExchange(
            client,
            properties.maximumResponseBytes()
        );
        PrometheusResponseParser parser = new PrometheusResponseParser(
            objectMapper,
            properties.maximumSeries(),
            properties.maximumPoints()
        );
        return new PrometheusObservabilityConnector(
            properties,
            exchange,
            parser,
            new PrometheusQueryCatalog(),
            gatewayClock
        );
    }
}
