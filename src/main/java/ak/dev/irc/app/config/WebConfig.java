package ak.dev.irc.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC-level config. CORS is handled exclusively by
 * {@link SecurityConfig#corsConfigurationSource()} so that there is
 * only one source of truth for allowed origins/methods/headers.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // CORS is configured in SecurityConfig — do NOT add addCorsMappings here
    // to avoid conflicts with the Spring Security CORS filter.
}