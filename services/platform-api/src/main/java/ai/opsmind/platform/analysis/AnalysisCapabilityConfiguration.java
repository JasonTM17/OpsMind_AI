package ai.opsmind.platform.analysis;

import java.security.SecureRandom;
import java.time.Clock;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "opsmind.ai-runtime.capability",
    name = "enabled",
    havingValue = "true"
)
class AnalysisCapabilityConfiguration {

    @Bean
    AnalysisCapabilityTokenIssuer analysisCapabilityTokenIssuer(
        AnalysisCapabilityProperties properties,
        ObjectMapper objectMapper
    ) {
        properties.validateEnabled();
        return new RsaAnalysisCapabilityTokenIssuer(
            Pkcs8RsaPrivateKeyLoader.load(properties.privateKeyPath()),
            properties.keyId(),
            properties.issuer(),
            properties.audience(),
            properties.maximumLifetime(),
            Clock.systemUTC(),
            new SecureRandom(),
            objectMapper
        );
    }
}
