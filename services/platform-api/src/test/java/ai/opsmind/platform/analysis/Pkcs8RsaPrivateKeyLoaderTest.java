package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pkcs8RsaPrivateKeyLoaderTest {

    @Test
    void loadsSecretMountedPkcs8RsaKey(@TempDir Path directory) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey generated = generator.generateKeyPair().getPrivate();
        String begin = "-----BEGIN " + "PRIVATE KEY-----";
        String end = "-----END " + "PRIVATE KEY-----";
        String pem = begin + "\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(generated.getEncoded())
            + "\n" + end + "\n";
        Path file = directory.resolve("capability-signing.pem");
        Files.writeString(file, pem, StandardCharsets.US_ASCII);

        PrivateKey loaded = Pkcs8RsaPrivateKeyLoader.load(file);

        assertThat(loaded).isInstanceOf(RSAPrivateKey.class);
        assertThat(loaded.getEncoded()).isEqualTo(generated.getEncoded());
    }
}
