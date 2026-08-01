package ai.opsmind.platform.evidence.artifact.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

final class ArtifactSourceTestFixtures {

    private ArtifactSourceTestFixtures() { }

    static final class BlockingAfterBodyInputStream extends InputStream {

        private final ByteArrayInputStream body;
        private final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        BlockingAfterBodyInputStream(byte[] body) {
            this.body = new ByteArrayInputStream(body);
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (body.available() > 0) return body.read(destination, offset, length);
            readStarted.countDown();
            awaitIgnoringInterrupt(release);
            return -1;
        }

        @Override
        public int read() {
            if (body.available() > 0) return body.read();
            readStarted.countDown();
            awaitIgnoringInterrupt(release);
            return -1;
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    static final class CloseBlockingInputStream extends ByteArrayInputStream {

        final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        CloseBlockingInputStream(byte[] body) {
            super(body);
        }

        @Override
        public void close() {
            closeStarted.countDown();
            awaitIgnoringInterrupt(closeRelease);
            closed.countDown();
        }

        void releaseClose() {
            closeRelease.countDown();
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
