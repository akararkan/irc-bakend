package ak.dev.irc.app.audit.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuditLoggingInterceptor} on every API path. The interceptor
 * itself decides which paths to skip, so the registration scope is wide.
 */
@Configuration
@RequiredArgsConstructor
public class AuditWebMvcConfig implements WebMvcConfigurer {

    private final AuditLoggingInterceptor interceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/**");
    }
}
