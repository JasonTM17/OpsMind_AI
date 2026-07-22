package ai.opsmind.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    private static final Set<String> ACCEPTED_MODES = Set.of("fail-closed", "oidc");

    @Bean
    SecurityFilterChain platformSecurityFilterChain(
        HttpSecurity http,
        PlatformSecurityProperties properties,
        SecurityProblemWriter problemWriter,
        ObjectProvider<ActivePlatformUserFilter> activeUserFilterProvider
    ) throws Exception {
        if (!ACCEPTED_MODES.contains(properties.mode())) {
            throw new IllegalStateException("Unsupported OPSMIND_SECURITY_MODE: " + properties.mode());
        }

        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                    request,
                    response,
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "identity.unauthenticated",
                    "A valid access token is required."
                ))
                .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                    request,
                    response,
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "authorization.denied",
                    "The verified principal is not authorized for this operation."
                )))
            .authorizeHttpRequests(authorize -> {
                authorize.requestMatchers("/actuator/health", "/error").permitAll();
                if ("oidc".equals(properties.mode())) {
                    authorize.requestMatchers("/api/v1/**").authenticated();
                }
                else {
                    authorize.requestMatchers("/api/v1/**").denyAll();
                }
                authorize.anyRequest().denyAll();
            });

        if ("oidc".equals(properties.mode())) {
            http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        }
        ActivePlatformUserFilter activeUserFilter = activeUserFilterProvider.getIfAvailable();
        if (activeUserFilter != null) {
            http.addFilterAfter(activeUserFilter, BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "opsmind.security", name = "mode", havingValue = "oidc")
    JwtDecoder oidcJwtDecoder(PlatformSecurityProperties properties) {
        properties.validateOidcMode();
        RestTemplate oidcHttp = oidcHttpClient(properties);
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withIssuerLocation(properties.issuerUri().toString())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(oidcHttp)
            .build();
        decoder.setJwtValidator(oidcValidators(properties, Clock.systemUTC()));
        return decoder;
    }

    static RestTemplate oidcHttpClient(PlatformSecurityProperties properties) {
        properties.validateOidcMode();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(500));
        requestFactory.setReadTimeout(Duration.ofMillis(500));
        var restTemplate = new RestTemplate(requestFactory);
        restTemplate.getInterceptors().add(
            new OidcOutboundRequestRateLimiter(properties.jwksRefreshMinimumInterval())
        );
        return restTemplate;
    }

    static OAuth2TokenValidator<Jwt> oidcValidators(
        PlatformSecurityProperties properties,
        Clock clock
    ) {
        properties.validateOidcMode();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(properties.clockSkew());
        timestampValidator.setClock(clock);
        return JwtValidators.createDefaultWithValidators(List.of(
            timestampValidator,
            new JwtIssuerValidator(properties.issuerUri().toString()),
            new OidcAccessTokenValidator(properties, clock)
        ));
    }
}
