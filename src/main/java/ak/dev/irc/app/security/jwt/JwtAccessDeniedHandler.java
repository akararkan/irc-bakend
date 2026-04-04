package ak.dev.irc.app.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import ak.dev.irc.app.common.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Returns a JSON 403 when an authenticated user lacks required permissions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        String traceId = UUID.randomUUID().toString();

        log.warn("[{}] Access denied for {} {} — {}",
                traceId, request.getMethod(), request.getRequestURI(),
                accessDeniedException.getMessage());

        ApiErrorResponse error = ApiErrorResponse.builder()
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("You do not have the required permissions to access this resource.")
                .path(request.getRequestURI())
                .errorCode("ACCESS_DENIED")
                .traceId(traceId)
                .build();

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
