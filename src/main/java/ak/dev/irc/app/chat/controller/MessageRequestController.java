package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.response.MessageRequestResponse;
import ak.dev.irc.app.chat.enums.MessageRequestStatus;
import ak.dev.irc.app.chat.service.MessageRequestService;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.messages.ChatMessages;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** The Message Requests inbox — first contact from people you're not connected to. */
@RestController
@RequestMapping("/api/v1/message-requests")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MessageRequestController {

    private final MessageRequestService requestService;

    @GetMapping
    public ResponseEntity<Page<MessageRequestResponse>> list(
            @RequestParam(defaultValue = "PENDING") MessageRequestStatus status,
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(requestService.list(requireId(user), status, pageable));
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> count(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of("count", requestService.countPending(requireId(user))));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Void> accept(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        requestService.accept(id, requireId(user));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Void> decline(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        requestService.decline(id, requireId(user));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<Void> block(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        requestService.block(id, requireId(user));
        return ResponseEntity.ok().build();
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException(ChatMessages.AUTH_REQUIRED_MSG);
        return user.getId();
    }
}
