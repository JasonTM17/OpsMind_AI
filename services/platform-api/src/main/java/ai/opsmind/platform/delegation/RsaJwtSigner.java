package ai.opsmind.platform.delegation;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Minimal RS256 JWT primitive shared by otherwise separate capability domains. */
public final class RsaJwtSigner {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int RANDOM_IDENTIFIER_BYTES = 24;

    private final PrivateKey privateKey;
    private final String keyId;
    private final SecureRandom random;
    private final ObjectMapper objectMapper;

    public RsaJwtSigner(
        PrivateKey privateKey,
        String keyId,
        SecureRandom random,
        ObjectMapper objectMapper
    ) {
        if (!(privateKey instanceof RSAPrivateKey rsaKey) || rsaKey.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("Capability signing key must be RSA-2048 or stronger.");
        }
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Capability key ID is invalid.");
        }
        this.privateKey = privateKey;
        this.keyId = keyId;
        this.random = Objects.requireNonNull(random, "random");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String sign(Map<String, Object> claims) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("kid", keyId);
        header.put("typ", "JWT");
        String signingInput = encode(header) + "." + encode(claims);
        return signingInput + "." + URL_ENCODER.encodeToString(signature(signingInput));
    }

    public String randomIdentifier() {
        byte[] value = new byte[RANDOM_IDENTIFIER_BYTES];
        random.nextBytes(value);
        return URL_ENCODER.encodeToString(value);
    }

    private String encode(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Capability claims could not be encoded.", exception);
        }
    }

    private byte[] signature(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey, random);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Capability signing failed.", exception);
        }
    }
}
