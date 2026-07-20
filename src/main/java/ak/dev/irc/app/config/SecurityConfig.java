package ak.dev.irc.app.config;

import ak.dev.irc.app.security.jwt.JwtAccessDeniedHandler;
import ak.dev.irc.app.security.jwt.JwtAuthenticationEntryPoint;
import ak.dev.irc.app.security.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Method security (@PreAuthorize enforcement) is ON by default. The whole
     * authorization surface of this app is annotation-driven — over a hundred
     * {@code @PreAuthorize} rules on the controllers — so this nested config
     * is what actually locks the API down. Set {@code app.security.permit-all=true}
     * (env var SECURITY_PERMIT_ALL) only for throwaway local testing where you
     * want annotations ignored; it must never be set in production.
     */
    @Configuration
    @ConditionalOnProperty(name = "app.security.permit-all", havingValue = "false", matchIfMissing = true)
    @EnableMethodSecurity
    public static class MethodSecurityConfig {
    }

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final JwtAuthenticationEntryPoint jwtEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final UserDetailsService userDetailsService;

    @Value("${app.security.permit-all:false}")
    private boolean permitAll;

    /**
     * Extra origins from {@code CORS_ORIGINS} env var (comma-separated, exact).
     * Combined with the always-on baseline below (localhost + Vercel
     * production domain). Set in Railway → Variables → CORS_ORIGINS to add
     * additional production frontend domain(s) without a redeploy.
     */
    @Value("${app.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        if (permitAll) {
            log.warn("IRC Security Filter Chain — app.security.permit-all=true: "
                    + "@PreAuthorize enforcement is OFF. Local testing only — never set in production.");
        } else {
            log.info("IRC Security Filter Chain — method security ON (@PreAuthorize enforced).");
        }

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))

                // Endpoint-level authorization lives on the controllers as
                // @PreAuthorize annotations (enforced by MethodSecurityConfig
                // above). The chain stays permissive so optional-auth endpoints
                // (anonymous view counts, public profiles, who-to-follow) keep
                // working — with one belt-and-braces rule: admin routes require
                // the ADMIN role at the chain level too, so a forgotten
                // annotation can never expose them.
                .authorizeHttpRequests(auth -> {
                    if (!permitAll) {
                        auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    }
                    auth.anyRequest().permitAll();
                })

                // JWT filter enabled so SecurityContext is populated from Bearer tokens.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        // Collapse "user not found" into BadCredentials so login responses
        // can't be used to enumerate which emails are registered.
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // ── Exact origins only ──────────────────────────────────────────────
        // The CORS spec forbids `Access-Control-Allow-Origin: *` together with
        // credentials, and exact origins are the simplest, most predictable
        // shape for `allowCredentials=true`. Each entry is matched on
        // scheme+host+port — never path, never trailing slash.
        //
        // Add a new production domain here (then redeploy), OR add it without
        // a redeploy via the CORS_ORIGINS env var on Railway (comma-separated
        // exact origins, same format).
        List<String> origins = new ArrayList<>(List.of(
                // production
                "https://ika-frontend-six.vercel.app",
                // local Vite dev server (default + common alt port)
                "http://localhost:5173",
                "http://localhost:5174",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:5174",
                // local Next.js / generic dev server
                "http://localhost:3000",
                "http://127.0.0.1:3000"
        ));

        // Merge in anything the operator set via CORS_ORIGINS env var.
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isBlank()) {
            Arrays.stream(corsAllowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .forEach(origins::add);
        }
        log.info("CORS allowed origins: {}", origins);

        config.setAllowedOrigins(origins);
        // LAN dev: phones/tablets hitting the Vite/Next dev server over WiFi.
        // Patterns are required because `allowCredentials=true` forbids "*" in
        // setAllowedOrigins but works fine with setAllowedOriginPatterns.
        config.setAllowedOriginPatterns(List.of(
                "http://192.168.*.*:5173", "http://192.168.*.*:5174", "http://192.168.*.*:3000",
                "http://10.*.*.*:5173",    "http://10.*.*.*:5174",    "http://10.*.*.*:3000"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Set-Cookie", "Content-Disposition"));
        // Required for cookie-based auth + the IRC_TOKEN refresh flow.
        // Note: with credentials=true the CORS spec FORBIDS "*" for origin,
        // which is exactly why we use the exact allowlist above.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
