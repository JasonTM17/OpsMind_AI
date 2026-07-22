package ai.opsmind.platform.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

final class Pkcs8RsaPrivateKeyLoader {

    private static final int MAX_KEY_FILE_BYTES = 65_536;
    private static final String BEGIN = "-----BEGIN " + "PRIVATE KEY-----";
    private static final String END = "-----END " + "PRIVATE KEY-----";

    private Pkcs8RsaPrivateKeyLoader() {
    }

    static PrivateKey load(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Analysis capability signing key is unavailable.");
            }
            byte[] fileBytes;
            try (InputStream stream = Files.newInputStream(path)) {
                fileBytes = stream.readNBytes(MAX_KEY_FILE_BYTES + 1);
            }
            if (fileBytes.length == 0 || fileBytes.length > MAX_KEY_FILE_BYTES) {
                throw new IllegalStateException("Analysis capability signing key size is invalid.");
            }
            String pem = new String(fileBytes, StandardCharsets.US_ASCII).trim();
            if (!pem.startsWith(BEGIN) || !pem.endsWith(END)) {
                throw new IllegalStateException("Analysis capability signing key must be PKCS8 PEM.");
            }
            String encoded = pem.substring(BEGIN.length(), pem.length() - END.length())
                .replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))
            );
            if (!(key instanceof RSAPrivateKey rsaKey) || rsaKey.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("Analysis capability signing key is too weak.");
            }
            return key;
        }
        catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Analysis capability signing key could not be loaded.", exception);
        }
    }
}
