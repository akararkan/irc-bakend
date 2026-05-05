package ak.dev.irc.app.audit.web;

import ak.dev.irc.app.audit.entity.AuditLog;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.audit.enums.AuditOutcome;
import ak.dev.irc.app.audit.service.AuditLogService;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures every API request and persists an audit row.
 *
 * <p>Runs as a {@link HandlerInterceptor} (not a filter) so it executes
 * <em>inside</em> the Spring Security filter chain — the SecurityContext is
 * still populated when {@link #afterCompletion} fires, giving us reliable
 * access to the authenticated principal even on the response side.</p>
 *
 * <p>Body content is intentionally never read — only metadata is persisted,
 * so a request containing a password is recorded as a {@code POST
 * /api/v1/auth/login} with no payload leak.</p>
 *
 * <p>The audit write is async — request threads are released as soon as the
 * response is flushed. A failure to write the audit row never affects the
 * request (see {@link AuditLogService#recordAsync}).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLoggingInterceptor implements HandlerInterceptor {

    /** Patterns we never audit (too noisy or pathological for SSE streams). */
    private static final Pattern SKIP_PATTERN = Pattern.compile(
            "^(?:/actuator|/error|/favicon|/swagger|/v3/api-docs|/health).*"
                    + "|.*?/stream(/.*)?$"
                    + "|.*?/heartbeat$",
            Pattern.CASE_INSENSITIVE);

    /** Best-effort UUID detector inside path segments. */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String STARTED_AT_ATTR = "ak.audit.startedAt";

    private final AuditLogService auditService;
    private final UserRepository  userRepo;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                              @NonNull HttpServletResponse response,
                              @NonNull Object handler) {
        request.setAttribute(STARTED_AT_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                 @NonNull HttpServletResponse response,
                                 @NonNull Object handler,
                                 @Nullable Exception ex) {
        try {
            String path = request.getRequestURI();
            if (path == null || SKIP_PATTERN.matcher(path).matches()) return;

            Object startedObj = request.getAttribute(STARTED_AT_ATTR);
            long started = startedObj instanceof Long l ? l : System.currentTimeMillis();
            long duration = System.currentTimeMillis() - started;

            UUID userId = SecurityUtils.getCurrentUserId().orElse(null);
            String username = userId != null
                    ? userRepo.findById(userId).map(User::getUsername).orElse(null)
                    : null;

            AuditOperation operation = inferOperation(request);
            AuditOutcome   outcome   = inferOutcome(response, ex);
            ResourcePath   resource  = parseResource(path);

            AuditLog draft = AuditLog.builder()
                    .userId(userId)
                    .username(username)
                    .operation(operation)
                    .outcome(outcome)
                    .resourceType(resource.type)
                    .resourceId(resource.id)
                    .httpMethod(request.getMethod())
                    .path(path)
                    .queryString(truncate(request.getQueryString(), 1000))
                    .statusCode(response.getStatus())
                    .durationMs(duration)
                    .ipAddress(extractIp(request))
                    .userAgent(truncate(request.getHeader("User-Agent"), 400))
                    .summary(request.getMethod() + " " + path + " → " + response.getStatus())
                    .errorCode(ex != null ? ex.getClass().getSimpleName() : null)
                    .build();

            auditService.recordAsync(draft);

        } catch (Exception loggingEx) {
            // Audit must never break the request — swallow and move on.
            log.debug("[AUDIT-INTERCEPT] capture failed: {}", loggingEx.getMessage());
        }
    }

    private static AuditOperation inferOperation(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null) return AuditOperation.OTHER;
        String path = request.getRequestURI() == null ? "" : request.getRequestURI().toLowerCase();
        if (path.contains("/auth/login"))  return AuditOperation.LOGIN;
        if (path.contains("/auth/logout")) return AuditOperation.LOGOUT;
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("multipart/")) {
            return AuditOperation.UPLOAD;
        }
        return switch (method.toUpperCase()) {
            case "GET", "HEAD"      -> AuditOperation.READ;
            case "POST"             -> AuditOperation.CREATE;
            case "PUT", "PATCH"     -> AuditOperation.UPDATE;
            case "DELETE"           -> AuditOperation.DELETE;
            default                 -> AuditOperation.OTHER;
        };
    }

    private static AuditOutcome inferOutcome(HttpServletResponse response, Exception thrown) {
        if (thrown != null) return AuditOutcome.SERVER_ERROR;
        int status = response.getStatus();
        if (status >= 500) return AuditOutcome.SERVER_ERROR;
        if (status >= 400) return AuditOutcome.CLIENT_ERROR;
        if (status >= 300) return AuditOutcome.REDIRECT;
        return AuditOutcome.SUCCESS;
    }

    /**
     * Parse {@code /api/v1/posts/abc-123/comments/def-456} into the deepest
     * UUID-bearing resource — {@code (PostComment, def-456)} — with the
     * preceding path segment giving the resource type.
     */
    static ResourcePath parseResource(String path) {
        if (path == null) return new ResourcePath(null, null);
        String[] parts = path.split("/");
        UUID uuid = null;
        String typeSegment = null;
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (seg.isEmpty()) continue;
            Matcher m = UUID_PATTERN.matcher(seg);
            if (m.matches()) {
                try {
                    uuid = UUID.fromString(seg);
                    if (i > 0) typeSegment = parts[i - 1];
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (typeSegment == null) {
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isEmpty() && !"v1".equals(parts[i]) && !"api".equals(parts[i])) {
                    typeSegment = parts[i];
                    break;
                }
            }
        }
        return new ResourcePath(toResourceType(typeSegment), uuid);
    }

    private static String toResourceType(String pathSegment) {
        if (pathSegment == null) return null;
        return switch (pathSegment.toLowerCase()) {
            case "posts"           -> "Post";
            case "comments"        -> "PostComment";
            case "questions"       -> "Question";
            case "answers"         -> "QuestionAnswer";
            case "reanswers", "replies" -> "QuestionAnswer";
            case "research"        -> "Research";
            case "users"           -> "User";
            case "notifications"   -> "Notification";
            case "audit"           -> "AuditLog";
            case "search"          -> "Search";
            default                -> Character.toUpperCase(pathSegment.charAt(0))
                                       + pathSegment.substring(1);
        };
    }

    private static String extractIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip.trim();
        return request.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    record ResourcePath(String type, UUID id) {}
}
