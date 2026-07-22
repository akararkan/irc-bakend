package ak.dev.irc.app.chat.controller;

import ak.dev.irc.app.chat.dto.request.*;
import ak.dev.irc.app.chat.dto.response.MessagePage;
import ak.dev.irc.app.chat.dto.response.MessageResponse;
import ak.dev.irc.app.chat.dto.response.ReactionSummary;
import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.chat.service.MessageQueryService;
import ak.dev.irc.app.chat.service.MessageService;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The message log — Cassandra-backed cursor reads (newest→older bucket walk),
 * idempotent send, edit/soft-delete, reactions, forward, delivered receipts, and
 * bounded in-conversation search.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MessageController {

    private final MessageService messageService;
    private final MessageQueryService messageQueryService;
    private final S3StorageService storageService;

    // ── Read ─────────────────────────────────────────────────────────────────────

    /** Page of messages, newest→older. Pass the previous {@code nextCursor} to page back. */
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<MessagePage<MessageResponse>> messages(
            @PathVariable UUID id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.loadPage(id, requireId(user), cursor, Pages.clamp(limit)));
    }

    /** Gap sync — everything strictly newer than the client's high-water id, ascending. */
    @GetMapping("/conversations/{id}/messages/sync")
    public ResponseEntity<List<MessageResponse>> sync(
            @PathVariable UUID id,
            @RequestParam long after,
            @RequestParam(defaultValue = "100") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.sync(id, requireId(user), after, Pages.clamp(limit)));
    }

    @GetMapping("/conversations/{id}/messages/search")
    public ResponseEntity<List<MessageResponse>> search(
            @PathVariable UUID id,
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.search(id, requireId(user), q, Pages.clamp(limit)));
    }

    /** Cross-conversation search over everything the caller can read (Elasticsearch). */
    @GetMapping("/messaging/search")
    public ResponseEntity<List<MessageResponse>> searchAll(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.searchAll(requireId(user), q, Pages.clamp(limit)));
    }

    /** Pinned messages of a conversation (newest pin first). */
    @GetMapping("/conversations/{id}/pinned")
    public ResponseEntity<List<MessageResponse>> pinned(@PathVariable UUID id,
                                                        @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.pinnedMessages(id, requireId(user)));
    }

    @GetMapping("/messages/{messageId}")
    public ResponseEntity<MessageResponse> one(@PathVariable long messageId,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.getOne(messageId, requireId(user)));
    }

    @GetMapping("/messages/{messageId}/reactions")
    public ResponseEntity<List<ReactionSummary>> reactions(@PathVariable long messageId,
                                                           @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.reactions(messageId, requireId(user)));
    }

    // ── Write ────────────────────────────────────────────────────────────────────

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<MessageResponse> send(@PathVariable UUID id,
                                                @Valid @RequestBody SendMessageRequest req,
                                                @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messageService.send(id, requireId(user), req));
    }

    /**
     * Convenience multipart send: uploads raw files through the existing R2 media
     * pipeline and sends them as one message. Rich metadata (voice waveform,
     * dimensions) should use the JSON endpoint with pre-uploaded storage keys.
     */
    @PostMapping(value = "/conversations/{id}/messages/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MessageResponse> sendMultipart(
            @PathVariable UUID id,
            @RequestParam String clientNonce,
            @RequestParam(required = false) String body,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal User user) {
        UUID userId = requireId(user);
        boolean hasFiles = files != null && !files.isEmpty();
        if (!hasFiles && !StringUtils.hasText(body)) {
            throw new BadRequestException("Provide a body and/or at least one file.");
        }

        SendMessageRequest req = new SendMessageRequest();
        req.setClientNonce(clientNonce);
        req.setBody(body);

        List<MediaRefDto> media = new ArrayList<>();
        List<String> uploadedKeys = new ArrayList<>();
        try {
            if (hasFiles) {
                for (MultipartFile f : files) {
                    String key = storageService.upload(f, "chat/media");
                    uploadedKeys.add(key);
                    MediaRefDto m = new MediaRefDto();
                    m.setKind(classifyKind(f.getContentType()));
                    m.setStorageKey(key);
                    m.setUrl(storageService.getPublicUrl(key));
                    m.setMime(f.getContentType());
                    m.setBytes(f.getSize());
                    m.setFileName(f.getOriginalFilename());
                    media.add(m);
                }
                req.setMedia(media);
                req.setType(MessageType.valueOf(media.get(0).getKind()));
            } else {
                req.setType(MessageType.TEXT);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(messageService.send(id, userId, req));
        } catch (RuntimeException ex) {
            // Roll back any orphaned uploads on failure.
            for (String k : uploadedKeys) {
                try { storageService.delete(k); } catch (Exception ignore) { /* best-effort */ }
            }
            throw ex;
        }
    }

    @PatchMapping("/messages/{messageId}")
    public ResponseEntity<MessageResponse> edit(@PathVariable long messageId,
                                                @Valid @RequestBody EditMessageRequest req,
                                                @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageService.edit(messageId, requireId(user), req.getBody()));
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> delete(@PathVariable long messageId,
                                       @AuthenticationPrincipal User user) {
        messageService.delete(messageId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{messageId}/react")
    public ResponseEntity<List<ReactionSummary>> react(@PathVariable long messageId,
                                                       @Valid @RequestBody ReactRequest req,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageService.react(messageId, requireId(user), req.getEmoji()));
    }

    @DeleteMapping("/messages/{messageId}/react")
    public ResponseEntity<List<ReactionSummary>> unreact(@PathVariable long messageId,
                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageService.unreact(messageId, requireId(user)));
    }

    @PostMapping("/messages/{messageId}/forward")
    public ResponseEntity<MessageResponse> forward(@PathVariable long messageId,
                                                   @Valid @RequestBody ForwardMessageRequest req,
                                                   @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messageService.forward(
                messageId, requireId(user), req.getTargetConversationId(), req.getClientNonce()));
    }

    @PostMapping("/messages/{messageId}/delivered")
    public ResponseEntity<Void> delivered(@PathVariable long messageId,
                                          @AuthenticationPrincipal User user) {
        messageService.markDelivered(messageId, requireId(user));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/conversations/{id}/messages/{messageId}/pin")
    public ResponseEntity<Void> pin(@PathVariable UUID id, @PathVariable long messageId,
                                    @AuthenticationPrincipal User user) {
        messageService.pinMessage(id, messageId, requireId(user));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/conversations/{id}/messages/{messageId}/pin")
    public ResponseEntity<Void> unpin(@PathVariable UUID id, @PathVariable long messageId,
                                      @AuthenticationPrincipal User user) {
        messageService.unpinMessage(id, messageId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static String classifyKind(String contentType) {
        if (contentType == null) return "FILE";
        if (contentType.startsWith("image/")) return "IMAGE";
        if (contentType.startsWith("video/")) return "VIDEO";
        if (contentType.startsWith("audio/")) return "VOICE";
        return "FILE";
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return user.getId();
    }
}
