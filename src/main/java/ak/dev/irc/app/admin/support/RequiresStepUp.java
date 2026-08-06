package ak.dev.irc.app.admin.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an admin handler method (or a whole controller) as requiring a fresh
 * step-up marker ({@code stepup:{userId}} in Redis, armed via
 * {@code POST /api/v1/security/step-up}).
 * <p>
 * Enforced by {@link StepUpGuardInterceptor} on every {@code /api/v1/admin/**}
 * route — absent marker → 403 {@code STEP_UP_REQUIRED}. Apply to every
 * mutation the admin blueprint marks danger {@code high} or {@code critical}.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresStepUp {
}
