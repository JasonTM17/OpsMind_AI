package ai.opsmind.platform.delegation;

import java.security.SecureRandom;
import java.time.Clock;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "opsmind.tool-gateway.capability",
    name = "enabled",
    havingValue = "true"
)
class ToolCapabilityConfiguration {

    @Bean
    ToolCapabilityTokenIssuer toolCapabilityTokenIssuer(
        ToolCapabilityProperties properties,
        ObjectMapper objectMapper
    ) {
        properties.validateEnabled();
        return new RsaToolCapabilityTokenIssuer(
            Pkcs8RsaPrivateKeyLoader.load(properties.privateKeyPath()),
            properties.keyId(),
            properties.issuer(),
            properties.audience(),
            properties.authorizedParty(),
            properties.maximumLifetime(),
            Clock.systemUTC(),
            new SecureRandom(),
            objectMapper
        );
    }
}
