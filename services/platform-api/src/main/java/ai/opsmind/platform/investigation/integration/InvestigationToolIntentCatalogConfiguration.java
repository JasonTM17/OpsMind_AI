package ai.opsmind.platform.investigation.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "enabled", havingValue = "true")
class InvestigationToolIntentCatalogConfiguration {

    @Bean
    InvestigationToolIntentCatalog investigationToolIntentCatalog(ObjectMapper objectMapper) {
        return InvestigationToolIntentCatalogResourceLoader.load(objectMapper);
    }
}
