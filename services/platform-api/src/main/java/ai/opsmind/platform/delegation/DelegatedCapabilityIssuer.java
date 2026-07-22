package ai.opsmind.platform.delegation;

public interface DelegatedCapabilityIssuer {

    /**
     * Issues a short-lived signed capability through a configured standard JWT
     * implementation. Implementations must never accept actor/tenant values
     * from an unverified request body.
     */
    String issue(DelegatedCapability capability);
}
