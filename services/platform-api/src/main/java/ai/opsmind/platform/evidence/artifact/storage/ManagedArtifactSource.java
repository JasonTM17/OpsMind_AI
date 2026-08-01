package ai.opsmind.platform.evidence.artifact.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Storage-owned replayable spool source. A single pinned descriptor prevents
 * path replacement from changing the bytes observed by SDK replay passes.
 */
public final class ManagedArtifactSource implements AutoCloseable {

    @FunctionalInterface
    interface StreamOpener {
        InputStream open() throws IOException;
    }

    @FunctionalInterface
    interface SizeReader {
        long size() throws IOException;
    }

    private final StreamOpener streamOpener;
    private final SizeReader sizeReader;
    private final Closeable cancellationHandle;
    private final AtomicBoolean cleanupRequested = new AtomicBoolean();
    private final List<InputStream> activeStreams = new ArrayList<>();
    private boolean closed;

    private ManagedArtifactSource(
        StreamOpener streamOpener,
        SizeReader sizeReader,
        Closeable cancellationHandle
    ) {
        this.streamOpener = Objects.requireNonNull(streamOpener, "Artifact source opener is required.");
        this.sizeReader = Objects.requireNonNull(sizeReader, "Artifact source size reader is required.");
        this.cancellationHandle = Objects.requireNonNull(
            cancellationHandle,
            "Artifact source cancellation handle is required."
        );
    }

    /** Opens and pins a non-symlink spool file before transferring ownership to storage. */
    public static ManagedArtifactSource open(Path path) throws IOException {
        Path spoolPath = Objects.requireNonNull(path, "Artifact spool path is required.")
            .toAbsolutePath()
            .normalize();
        FileChannel channel = FileChannel.open(
            spoolPath,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        );
        return new ManagedArtifactSource(
            () -> new PositionalFileChannelInputStream(channel),
            channel::size,
            channel
        );
    }

    static ManagedArtifactSource forTesting(
        StreamOpener streamOpener,
        SizeReader sizeReader,
        Closeable cancellationHandle
    ) {
        return new ManagedArtifactSource(streamOpener, sizeReader, cancellationHandle);
    }

    synchronized InputStream openStream() throws IOException {
        requireOpen();
        InputStream stream = streamOpener.open();
        activeStreams.add(stream);
        return stream;
    }

    synchronized long size() throws IOException {
        requireOpen();
        return sizeReader.size();
    }

    boolean claimCleanup() {
        return cleanupRequested.compareAndSet(false, true);
    }

    void abort() {
        List<InputStream> streams;
        synchronized (this) {
            if (closed) return;
            closed = true;
            streams = List.copyOf(activeStreams);
            activeStreams.clear();
        }
        closeQuietly(cancellationHandle);
        for (InputStream stream : streams) closeQuietly(stream);
    }

    @Override
    public void close() {
        abort();
    }

    private void requireOpen() throws IOException {
        if (closed || cleanupRequested.get()) {
            throw new IOException("Artifact source is closed.");
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
            // Cleanup remains detached and never emits provider details.
        }
    }

    private static final class PositionalFileChannelInputStream extends InputStream {

        private final FileChannel channel;
        private long position;

        private PositionalFileChannelInputStream(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int read = read(oneByte, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(oneByte[0]);
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, destination.length);
            if (length == 0) return 0;
            int read = channel.read(ByteBuffer.wrap(destination, offset, length), position);
            if (read > 0) position += read;
            return read;
        }

        @Override
        public void close() {
            // The managed source owns the shared descriptor.
        }
    }
}
