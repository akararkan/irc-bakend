package ak.dev.irc.app.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import ak.dev.irc.app.common.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Returns a JSON 401 when an unauthenticated user hits a secured endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String traceId = UUID.randomUUID().toString();

        log.warn("[{}] Unauthenticated request to {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(),
                authException.getMessage());

        ApiErrorResponse error = ApiErrorResponse.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("You must be authenticated to access this resource. " +
                         "Please log in or provide a valid token.")
                .path(request.getRequestURI())
                .errorCode("AUTH_REQUIRED")
                .traceId(traceId)
                .build();

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
