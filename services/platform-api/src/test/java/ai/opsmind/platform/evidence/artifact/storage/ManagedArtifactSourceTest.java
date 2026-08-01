package ai.opsmind.platform.evidence.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedArtifactSourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void opensIndependentViewsOverOnePinnedDescriptorAndReleasesIt() throws Exception {
        byte[] body = "durable evidence".getBytes(StandardCharsets.UTF_8);
        Path spool = temporaryDirectory.resolve("artifact.spool");
        Files.write(spool, body);

        ManagedArtifactSource source = ManagedArtifactSource.open(spool);
        assertThat(source.size()).isEqualTo(body.length);

        try (InputStream first = source.openStream(); InputStream second = source.openStream()) {
            assertThat(first.readNBytes(4)).isEqualTo("dura".getBytes(StandardCharsets.UTF_8));
            assertThat(second.readAllBytes()).isEqualTo(body);
            assertThat(first.readAllBytes()).isEqualTo("ble evidence".getBytes(StandardCharsets.UTF_8));
        }

        source.close();
        assertThatThrownBy(source::openStream).isInstanceOf(java.io.IOException.class);
        assertThatCode(() -> Files.delete(spool)).doesNotThrowAnyException();
    }
}
