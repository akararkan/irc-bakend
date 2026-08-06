package ak.dev.irc.app.admin.support;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.security.stepup.StepUpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Enforces {@link RequiresStepUp} on admin handlers.
 * <p>
 * Registered only on {@code /api/v1/admin/**} ({@link AdminWebMvcConfig}), so
 * the annotation is inert outside the admin surface. Throws the same
 * {@code ForbiddenException(STEP_UP_REQUIRED)} the settings module uses, which
 * the global handler renders through the canonical error envelope.
 */
@Component
@RequiredArgsConstructor
public class StepUpGuardInterceptor implements HandlerInterceptor {

    private final StepUpService stepUpService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresStepUp guard = handlerMethod.getMethodAnnotation(RequiresStepUp.class);
        if (guard == null) {
            guard = handlerMethod.getBeanType().getAnnotation(RequiresStepUp.class);
        }
        if (guard != null) {
            UUID adminId = SecurityUtils.requireCurrentUserId();
            stepUpService.requireRecentStepUp(adminId);
        }
        return true;
    }
}
