package ai.opsmind.toolgateway.config;

import ai.opsmind.toolgateway.api.GatewayProblemWriter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GatewaySecurityConfiguration {

    @Bean
    SecurityFilterChain gatewaySecurityFilterChain(
        HttpSecurity http,
        GatewayProblemWriter problemWriter,
        GatewaySettings settings
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(form -> form.disable())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                    response,
                    401,
                    "caller.unauthenticated",
                    "Authenticated platform workload identity is required."
                ))
                .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                    response,
                    403,
                    "caller.unauthorized",
                    "The workload is not authorized to invoke the Tool Gateway."
                ))
            )
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/ready").permitAll()
                .requestMatchers("/internal/v1/tools/execute").authenticated()
                .anyRequest().denyAll()
            );
        configureWorkloadAuthentication(http, settings);
        return http.build();
    }

    private void configureWorkloadAuthentication(HttpSecurity http, GatewaySettings settings)
        throws Exception {
        if (settings.workloadJwkSetUri() == null) return;

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
            settings.workloadJwkSetUri().toString()
        )
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(BoundedJwksHttpClient.create())
            .build();
        decoder.setJwtValidator(new WorkloadJwtValidator(settings));
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(decoder)));
    }
}
