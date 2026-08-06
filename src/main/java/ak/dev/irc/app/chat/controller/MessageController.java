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
import ak.dev.irc.app.common.messages.ChatMessages;
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
    private final ak.dev.irc.app.chat.service.StarService starService;
    private final ak.dev.irc.app.chat.service.ScheduledMessageService scheduledMessageService;
    private final ak.dev.irc.app.chat.service.PollService pollService;

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

    /** Shared-media gallery — messages of one media kind (IMAGE/VIDEO/VOICE/AUDIO/
     *  GIF/STICKER/FILE/LINK), newest first; cursor = last messageId of the page. */
    @GetMapping("/conversations/{id}/media")
    public ResponseEntity<List<MessageResponse>> gallery(
            @PathVariable UUID id,
            @RequestParam String kind,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "30") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.gallery(id, requireId(user), kind, before, Pages.clamp(limit)));
    }

    /** Posts of this conversation carrying an exact {@code #tag}, newest first. */
    @GetMapping("/conversations/{id}/messages/by-tag")
    public ResponseEntity<List<MessageResponse>> byTag(
            @PathVariable UUID id,
            @RequestParam String tag,
            @RequestParam(defaultValue = "30") int limit,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageQueryService.byTag(id, requireId(user), tag, Pages.clamp(limit)));
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
            @RequestParam(required = false, defaultValue = "false") boolean silent,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal User user) {
        UUID userId = requireId(user);
        boolean hasFiles = files != null && !files.isEmpty();
        if (!hasFiles && !StringUtils.hasText(body)) {
            throw new BadRequestException(ChatMessages.BODY_OR_FILE_REQUIRED_MSG);
        }

        SendMessageRequest req = new SendMessageRequest();
        req.setClientNonce(clientNonce);
        req.setBody(body);
        req.setSilent(silent);

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

    /** Delete a message. {@code scope=everyone} (default) tombstones it for the whole
     *  conversation (own message, or group admin); {@code scope=me} hides it for you only. */
    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> delete(@PathVariable long messageId,
                                       @RequestParam(defaultValue = "everyone") String scope,
                                       @AuthenticationPrincipal User user) {
        UUID uid = requireId(user);
        if ("me".equalsIgnoreCase(scope)) {
            messageService.deleteForMe(messageId, uid);
        } else {
            messageService.delete(messageId, uid);
        }
        return ResponseEntity.noContent().build();
    }

    // ── Star / bookmark ──────────────────────────────────────────────────────────

    @PostMapping("/messages/{messageId}/star")
    public ResponseEntity<Void> star(@PathVariable long messageId, @AuthenticationPrincipal User user) {
        starService.star(messageId, requireId(user));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/messages/{messageId}/star")
    public ResponseEntity<Void> unstar(@PathVariable long messageId, @AuthenticationPrincipal User user) {
        starService.unstar(messageId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── Seen-by (group read receipts) ────────────────────────────────────────────

    @GetMapping("/messages/{messageId}/seen-by")
    public ResponseEntity<List<ak.dev.irc.app.chat.dto.response.ParticipantSummary>> seenBy(
            @PathVariable long messageId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(messageService.seenBy(messageId, requireId(user)));
    }

    // ── Scheduled messages ───────────────────────────────────────────────────────

    @PostMapping("/conversations/{id}/messages/schedule")
    public ResponseEntity<ak.dev.irc.app.chat.dto.response.ScheduledMessageResponse> schedule(
            @PathVariable UUID id,
            @Valid @RequestBody ak.dev.irc.app.chat.dto.request.ScheduleMessageRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scheduledMessageService.schedule(id, requireId(user), req));
    }

    @GetMapping("/conversations/{id}/scheduled")
    public ResponseEntity<List<ak.dev.irc.app.chat.dto.response.ScheduledMessageResponse>> listScheduled(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(scheduledMessageService.listPending(id, requireId(user)));
    }

    @DeleteMapping("/messaging/scheduled/{scheduledId}")
    public ResponseEntity<Void> cancelScheduled(@PathVariable UUID scheduledId,
                                                @AuthenticationPrincipal User user) {
        scheduledMessageService.cancel(scheduledId, requireId(user));
        return ResponseEntity.noContent().build();
    }

    // ── Polls ────────────────────────────────────────────────────────────────────

    /** Cast (or change) a vote on a poll message. */
    @PostMapping("/messages/{messageId}/poll/votes")
    public ResponseEntity<ak.dev.irc.app.chat.dto.response.PollResponse> pollVote(
            @PathVariable long messageId,
            @Valid @RequestBody PollVoteRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pollService.vote(messageId, requireId(user), req));
    }

    /** Retract the caller's vote (regular polls only — quiz answers are final). */
    @DeleteMapping("/messages/{messageId}/poll/votes")
    public ResponseEntity<ak.dev.irc.app.chat.dto.response.PollResponse> pollRetract(
            @PathVariable long messageId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pollService.retract(messageId, requireId(user)));
    }

    /** Close voting (poll author, or a channel admin with the edit right). */
    @PostMapping("/messages/{messageId}/poll/close")
    public ResponseEntity<ak.dev.irc.app.chat.dto.response.PollResponse> pollClose(
            @PathVariable long messageId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pollService.close(messageId, requireId(user)));
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
        if (contentType.equals("image/gif")) return "GIF";
        if (contentType.startsWith("image/")) return "IMAGE";
        if (contentType.startsWith("video/")) return "VIDEO";
        if (contentType.startsWith("audio/")) return "AUDIO";
        return "FILE";
    }

    private static UUID requireId(User user) {
        if (user == null) throw new UnauthorizedException(ChatMessages.AUTH_REQUIRED_MSG);
        return user.getId();
    }
}
