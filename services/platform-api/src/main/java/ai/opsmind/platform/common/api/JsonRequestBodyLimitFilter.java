package ai.opsmind.platform.common.api;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds API JSON bodies before Jackson builds an in-memory object tree. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class JsonRequestBodyLimitFilter extends OncePerRequestFilter {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final int MINIMUM_LIMIT = 1_024;
    private static final int MAXIMUM_LIMIT = 1_048_576;

    private final PlatformProblemWriter problemWriter;
    private final int maximumBytes;

    public JsonRequestBodyLimitFilter(
        PlatformProblemWriter problemWriter,
        @Value("${opsmind.http.max-json-body-bytes:65536}") int maximumBytes
    ) {
        if (maximumBytes < MINIMUM_LIMIT || maximumBytes > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException("JSON request body limit is outside the safe range.");
        }
        this.problemWriter = problemWriter;
        this.maximumBytes = maximumBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        boolean json = contentType != null && contentType.split(";", 2)[0]
            .trim().toLowerCase(java.util.Locale.ROOT).matches("application/(?:[a-z0-9.+-]+\\+)?json");
        String pathWithinApplication = request.getServletPath();
        if (request.getPathInfo() != null) {
            pathWithinApplication += request.getPathInfo();
        }
        return !pathWithinApplication.startsWith("/api/v1/")
            || !BODY_METHODS.contains(request.getMethod())
            || !json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > maximumBytes) {
            reject(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problemWriter.write(
            request,
            response,
            HttpStatus.CONTENT_TOO_LARGE,
            "request.body-too-large",
            "The JSON request body exceeds the configured byte limit."
        );
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class CachedBodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        private CachedBodyInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return input.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            if (listener == null) throw new IllegalArgumentException("ReadListener is required.");
            try {
                if (!isFinished()) listener.onDataAvailable();
                if (isFinished()) listener.onAllDataRead();
            }
            catch (IOException exception) {
                listener.onError(exception);
            }
        }
    }
}
