package ak.dev.irc.app.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │                       Idempotency-Key Filter                             │
 * │                                                                          │
 * │  Clients can pass {@code Idempotency-Key: <opaque-token>} on POST/PUT/   │
 * │  PATCH/DELETE requests. The first matching request runs as normal; the  │
 * │  response (status + body) is cached in Redis for 24 h. Subsequent       │
 * │  requests with the same key replay the cached response without          │
 * │  re-executing the handler — safe retries for mobile clients on flaky   │
 * │  networks (a double-tap on the heart can no longer double-react).       │
 * │                                                                          │
 * │  Keyed by {@code idem:{actor-or-ip}:{key}} so a stolen key by a         │
 * │  different actor can't replay someone else's response.                  │
 * │                                                                          │
 * │  Only intercepts mutating verbs. Idempotent-by-spec verbs (GET, HEAD,   │
 * │  OPTIONS) are passed straight through.                                  │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
// Runs AFTER Spring Security's filter chain (its filters use negative orderings)
// so the SecurityContext is populated by the time we read the principal. Without
// this, every authenticated request fell back to {@code req.getRemoteAddr()} as
// the actor identifier — two users behind the same NAT/proxy would share an
// idempotency bucket and one user's POST could replay another's cached response.
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER  = "Idempotency-Key";
    private static final Duration TTL   = Duration.ofHours(24);
    private static final int MAX_BODY   = 64 * 1024;   // 64 KiB cap on cached body

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        String method = req.getMethod();
        if (key == null || key.isBlank() || !isMutating(method)) {
            chain.doFilter(req, res);
            return;
        }

        // Salt the cache key with the requester identity so a key leaked by one
        // user can't replay onto another's session.
        String actor = req.getUserPrincipal() != null
                ? req.getUserPrincipal().getName()
                : req.getRemoteAddr();
        String redisKey = "idem:" + actor + ":" + key;

        // Cache hit → replay verbatim.
        try {
            String cached = redis.opsForValue().get(redisKey);
            if (cached != null) {
                replay(res, cached);
                return;
            }
        } catch (Exception ex) {
            log.debug("[IDEM] redis read failed: {} — falling through", ex.getMessage());
        }

        // Cache miss — run the handler, capture the response, then store.
        CapturingResponse capture = new CapturingResponse(res);
        chain.doFilter(req, capture);
        capture.flushBuffer();

        if (capture.getStatus() < 400 && capture.body.size() <= MAX_BODY) {
            try {
                String envelope = objectMapper.writeValueAsString(Map.of(
                        "status",      capture.getStatus(),
                        "contentType", capture.getContentType() != null ? capture.getContentType() : "application/json",
                        "body",        capture.body.toString(StandardCharsets.UTF_8)));
                redis.opsForValue().set(redisKey, envelope, TTL);
            } catch (Exception ex) {
                log.debug("[IDEM] redis write failed: {}", ex.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void replay(HttpServletResponse res, String envelope) throws IOException {
        Map<String, Object> m;
        try {
            m = objectMapper.readValue(envelope, Map.class);
        } catch (Exception ex) {
            return; // bad envelope, just let the next call happen
        }
        Integer status = (Integer) m.get("status");
        String contentType = (String) m.get("contentType");
        String body = (String) m.get("body");
        if (status != null) res.setStatus(status);
        if (contentType != null) res.setContentType(contentType);
        res.setHeader("Idempotent-Replay", "true");
        if (body != null && !body.isEmpty()) res.getWriter().write(body);
    }

    private static boolean isMutating(String method) {
        return "POST".equals(method) || "PUT".equals(method)
            || "PATCH".equals(method) || "DELETE".equals(method);
    }

    /** Wraps the response so we can capture the body bytes after the handler writes them. */
    private static final class CapturingResponse extends HttpServletResponseWrapper {
        final ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
        private PrintWriter writer;
        private jakarta.servlet.ServletOutputStream stream;

        CapturingResponse(HttpServletResponse delegate) { super(delegate); }

        @Override public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new java.io.OutputStreamWriter(getOutputStream(), getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8"), true);
            }
            return writer;
        }

        @Override public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                final jakarta.servlet.ServletOutputStream original = super.getOutputStream();
                stream = new jakarta.servlet.ServletOutputStream() {
                    @Override public boolean isReady() { return original.isReady(); }
                    @Override public void setWriteListener(jakarta.servlet.WriteListener l) { original.setWriteListener(l); }
                    @Override public void write(int b) throws IOException {
                        body.write(b);
                        original.write(b);
                    }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        body.write(b, off, len);
                        original.write(b, off, len);
                    }
                    @Override public void flush() throws IOException { original.flush(); }
                };
            }
            return stream;
        }

        @Override public void flushBuffer() throws IOException {
            if (writer != null) writer.flush();
            if (stream != null) stream.flush();
            super.flushBuffer();
        }
    }
}
