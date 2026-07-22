package ai.opsmind.platform.identity;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ai.opsmind.platform.common.api.CorrelationIdFilter;
import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class ActivePlatformUserFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivePlatformUserFilter.class);

    private final JwtPrincipalMapper principalMapper;
    private final PlatformUserStatusVerifier statusVerifier;
    private final SecurityProblemWriter problemWriter;

    public ActivePlatformUserFilter(
        JwtPrincipalMapper principalMapper,
        PlatformUserStatusVerifier statusVerifier,
        SecurityProblemWriter problemWriter
    ) {
        this.principalMapper = principalMapper;
        this.statusVerifier = statusVerifier;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String pathWithinApplication = request.getServletPath();
        if (request.getPathInfo() != null) {
            pathWithinApplication += request.getPathInfo();
        }
        return !pathWithinApplication.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
            || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            statusVerifier.requireActive(principalMapper.map(jwtAuthentication.getToken()));
        }
        catch (IllegalArgumentException exception) {
            problemWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "identity.claims-invalid",
                "The access token claims are not acceptable."
            );
            return;
        }
        catch (PlatformProblemException exception) {
            if (exception.status().is5xxServerError()) {
                LOGGER.error(
                    "Active-user verification failed. traceId={} code={} status={}",
                    request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME),
                    exception.code(),
                    exception.status().value(),
                    exception
                );
            }
            problemWriter.write(
                request,
                response,
                exception.status(),
                exception.code(),
                exception.getMessage()
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
