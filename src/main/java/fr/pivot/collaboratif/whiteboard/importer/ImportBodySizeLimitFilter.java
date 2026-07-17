package fr.pivot.collaboratif.whiteboard.importer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Servlet filter enforcing the 50&nbsp;MB body-size limit on
 * {@code POST /whiteboard/boards/{boardId}/import/klaxoon} (US08.13.1), independently of the
 * active Spring profile — the acceptance criterion requires HTTP 413 <strong>before any
 * processing</strong> once the limit is exceeded, in every environment.
 *
 * <p>Runs ahead of Spring MVC dispatch (filters always execute before the {@code
 * DispatcherServlet}), so an oversized body never reaches Bean Validation, the controller, or the
 * {@link ImportRateLimitService} counter. Two independent guards, either sufficient alone:
 * <ol>
 *   <li>A fast rejection on the declared {@code Content-Length} header, when present and already
 *       over the limit — no byte of the body is read.</li>
 *   <li>A bounded read of the actual body (defends against a missing/lying {@code Content-Length},
 *       e.g. chunked transfer-encoding): the body is buffered up to {@link #MAX_IMPORT_BODY_BYTES}
 *       + 1 bytes; exceeding that aborts with 413 without ever handing a partial stream downstream.</li>
 * </ol>
 *
 * <p>On success, the already-read bytes are replayed to the rest of the chain via {@link
 * CachedBodyRequestWrapper} — Spring's {@code HttpMessageConverter} then reads the body exactly as
 * it would have from the original (unbuffered) request. Buffering up to 50&nbsp;MB in memory per
 * request is an accepted trade-off: this endpoint is both role-gated (OWNER/EDITOR only) and rate
 * limited to 5 calls/minute/board ({@link ImportRateLimitService}), so worst-case concurrent memory
 * pressure from this filter is bounded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ImportBodySizeLimitFilter extends OncePerRequestFilter {

    /** 50 MB, the acceptance criterion's exact limit ({@code 50 * 1024 * 1024} bytes). */
    static final long MAX_IMPORT_BODY_BYTES = 50L * 1024 * 1024;

    private static final String IMPORT_PATH_SUFFIX = "/import/klaxoon";
    private static final int READ_CHUNK_SIZE = 8192;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {
        if (!matchesImportEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_IMPORT_BODY_BYTES) {
            sendPayloadTooLarge(response);
            return;
        }
        byte[] body = readBounded(request.getInputStream());
        if (body == null) {
            sendPayloadTooLarge(response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequestWrapper(request, body), response);
    }

    /**
     * Returns whether this request targets the Klaxoon import endpoint — a {@code POST} whose
     * path ends with {@value #IMPORT_PATH_SUFFIX}, regardless of the {@code boardId} segment or
     * whether the servlet context path is included in {@link HttpServletRequest#getRequestURI()}.
     *
     * @param request the incoming request
     * @return {@code true} if this is a Klaxoon import POST
     */
    private boolean matchesImportEndpoint(final HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().endsWith(IMPORT_PATH_SUFFIX);
    }

    /**
     * Reads the entire request body, aborting as soon as more than {@link #MAX_IMPORT_BODY_BYTES}
     * bytes have been read.
     *
     * @param in the request's raw input stream
     * @return the full body bytes, or {@code null} if the limit was exceeded mid-read
     * @throws IOException if the underlying read fails
     */
    private byte[] readBounded(final InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_IMPORT_BODY_BYTES) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * Writes a minimal 413 Payload Too Large response, without leaking any request content.
     *
     * @param response the HTTP response
     * @throws IOException if writing the response fails
     */
    private void sendPayloadTooLarge(final HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write(
                "{\"title\":\"Payload too large\","
                        + "\"detail\":\"Import body exceeds the 50 MB limit\"}");
    }

    /**
     * Replays an already-fully-read request body as a fresh {@link ServletInputStream}, so
     * downstream code (Spring MVC's {@code HttpMessageConverter}) can read it exactly as it would
     * have from the original, unbuffered request.
     */
    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        /**
         * Wraps {@code request}, serving {@code body} in place of its original input stream.
         *
         * @param request the original request
         * @param body    the already-read body bytes
         */
        CachedBodyRequestWrapper(final HttpServletRequest request, final byte[] body) {
            super(request);
            this.body = body;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(final ReadListener readListener) {
                    // Synchronous replay only — no async read notifications are ever needed here.
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    getInputStream(),
                    encoding != null ? encoding : StandardCharsets.UTF_8.name()));
        }
    }
}
