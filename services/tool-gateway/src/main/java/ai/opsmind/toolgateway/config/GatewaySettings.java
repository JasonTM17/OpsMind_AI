package ai.opsmind.toolgateway.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("opsmind.tool-gateway")
public record GatewaySettings(
    URI capabilityIssuer,
    String capabilityAudience,
    String platformCallerId,
    URI jwkSetUri,
    URI workloadIssuer,
    String workloadAudience,
    String workloadScope,
    URI workloadJwkSetUri,
    Duration maximumCapabilityLifetime,
    int maximumRequestBytes,
    int maximumResultBytes
) {
    @ConstructorBinding
    public GatewaySettings {
        capabilityIssuer = capabilityIssuer == null
            ? URI.create("https://platform.invalid.example") : capabilityIssuer;
        capabilityAudience = capabilityAudience == null
            ? "opsmind-tool-gateway" : capabilityAudience;
        platformCallerId = platformCallerId == null
            ? "opsmind-platform-api" : platformCallerId;
        jwkSetUri = normalizeOptionalUri(jwkSetUri);
        workloadIssuer = workloadIssuer == null ? capabilityIssuer : workloadIssuer;
        workloadAudience = workloadAudience == null
            ? "opsmind-tool-gateway-workload" : workloadAudience;
        workloadScope = workloadScope == null ? "tool.execute" : workloadScope;
        workloadJwkSetUri = normalizeOptionalUri(workloadJwkSetUri);
        maximumCapabilityLifetime = maximumCapabilityLifetime == null
            ? Duration.ofMinutes(5) : maximumCapabilityLifetime;
        maximumRequestBytes = maximumRequestBytes == 0 ? 65_536 : maximumRequestBytes;
        maximumResultBytes = maximumResultBytes == 0 ? 262_144 : maximumResultBytes;

        requireHttps(capabilityIssuer, "Capability issuer");
        if (capabilityAudience.isBlank() || capabilityAudience.length() > 128) {
            throw new IllegalArgumentException("Capability audience is invalid.");
        }
        if (platformCallerId.isBlank() || platformCallerId.length() > 128) {
            throw new IllegalArgumentException("Platform caller identity is invalid.");
        }
        if (jwkSetUri != null) requireHttps(jwkSetUri, "JWKS URI");
        requireHttps(workloadIssuer, "Workload issuer");
        if (workloadAudience.isBlank() || workloadAudience.length() > 128) {
            throw new IllegalArgumentException("Workload audience is invalid.");
        }
        if (!workloadScope.matches("[A-Za-z0-9._:/-]{1,128}")) {
            throw new IllegalArgumentException("Workload scope is invalid.");
        }
        if (workloadJwkSetUri != null) requireHttps(workloadJwkSetUri, "Workload JWKS URI");
        if (maximumCapabilityLifetime.isNegative() || maximumCapabilityLifetime.isZero()
            || maximumCapabilityLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Maximum capability lifetime is invalid.");
        }
        if (maximumRequestBytes < 1_024 || maximumRequestBytes > 1_048_576
            || maximumResultBytes < 1_024 || maximumResultBytes > 10_485_760) {
            throw new IllegalArgumentException("Gateway byte bounds are invalid.");
        }
    }

    private static URI normalizeOptionalUri(URI value) {
        return value != null && value.toString().isBlank() ? null : value;
    }

    private static void requireHttps(URI value, String name) {
        if (!value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
            || value.getHost() == null || value.getRawUserInfo() != null
            || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException(name + " must be a routable HTTPS URI.");
        }
    }
}
