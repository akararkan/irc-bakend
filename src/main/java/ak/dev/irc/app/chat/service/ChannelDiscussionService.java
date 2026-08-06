package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.ChatCommentByPostEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.ChatCommentByPostRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.dto.AdminRights;
import ak.dev.irc.app.chat.dto.request.SendMessageRequest;
import ak.dev.irc.app.chat.dto.response.MessageResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.enums.MemberStatus;
import ak.dev.irc.app.chat.permission.ChannelRights;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.ChannelStreamMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Telegram-style discussion groups: a channel links one GROUP conversation and
 * every reply in that group to a channel post becomes a "comment" on it. This
 * service owns the link/unlink lifecycle and the comment read/write facade;
 * the comment itself is a perfectly normal group message (so edits, reactions,
 * deletes and realtime all just work), indexed by {@code comments_by_post}.
 */
@Service
@RequiredArgsConstructor
public class ChannelDiscussionService {

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatCommentByPostRepository commentRepo;
    private final MessageByIdRepository messageByIdRepo;
    private final MessageService messageService;
    private final MessageQueryService messageQueryService;

    // ── Link / unlink ────────────────────────────────────────────────────────────

    @Transactional
    public void link(UUID channelId, UUID actorId, UUID groupId) {
        Conversation channel = requireChannel(channelId);
        requireChangeInfo(channelId, actorId);
        Conversation group = conversationRepo.findById(groupId)
                .filter(c -> c.getDeletedAt() == null && c.isGroup())
                .orElseThrow(() -> new ResourceNotFoundException("Group", "id", groupId));
        // The actor must run the group too — linking someone else's group would
        // let a channel dump its audience into it.
        ConversationMember inGroup = memberRepo.findMember(groupId, actorId)
                .filter(m -> m.isActive() && m.isAdminOrOwner())
                .orElseThrow(() -> new ForbiddenException(
                        ChannelStreamMessages.DISCUSSION_LINK_ADMIN_REQUIRED_MSG, ChannelStreamMessages.ADMINS_ONLY));
        if (conversationRepo.findByLinkedGroupId(groupId)
                .filter(c -> !c.getId().equals(channelId)).isPresent()) {
            throw new BadRequestException(ChannelStreamMessages.DISCUSSION_GROUP_TAKEN_MSG);
        }
        channel.setLinkedGroupId(groupId);
        conversationRepo.save(channel);
    }

    @Transactional
    public void unlink(UUID channelId, UUID actorId) {
        Conversation channel = requireChannel(channelId);
        requireChangeInfo(channelId, actorId);
        channel.setLinkedGroupId(null);
        conversationRepo.save(channel);
    }

    // ── Comments ─────────────────────────────────────────────────────────────────

    /** The post's comment thread (newest first), readable by any channel member. */
    @Transactional(readOnly = true)
    public List<MessageResponse> comments(UUID channelId, long postId, UUID userId,
                                          Long before, int limit) {
        requireChannel(channelId);
        requireSubscriber(channelId, userId);
        requirePostOf(channelId, postId);
        var rows = before != null
                ? commentRepo.pageBefore(postId, before, limit)
                : commentRepo.firstPage(postId, limit);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (ChatCommentByPostEntity r : rows) ids.add(r.getCommentMessageId());
        return messageQueryService.messagesByIds(List.copyOf(ids), userId);
    }

    /**
     * Comment on a channel post: auto-joins the caller into the discussion group
     * (Telegram joins you on first comment) and sends a normal group message
     * replying to the post — the send path indexes it as a comment.
     */
    @Transactional
    public MessageResponse addComment(UUID channelId, long postId, UUID userId, SendMessageRequest req) {
        Conversation channel = requireChannel(channelId);
        requireSubscriber(channelId, userId);
        requirePostOf(channelId, postId);
        UUID groupId = channel.getLinkedGroupId();
        if (groupId == null) {
            throw new BadRequestException(ChannelStreamMessages.DISCUSSION_GROUP_MISSING_MSG);
        }
        Conversation group = conversationRepo.findById(groupId)
                .filter(c -> c.getDeletedAt() == null && c.isGroup())
                .orElseThrow(() -> new BadRequestException(ChannelStreamMessages.DISCUSSION_GROUP_GONE_MSG));
        autoJoin(group, userId);
        req.setReplyToId(postId);
        return messageService.send(groupId, userId, req);
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private void autoJoin(Conversation group, UUID userId) {
        ConversationMember existing = memberRepo.findMember(group.getId(), userId).orElse(null);
        if (existing != null && existing.isActive()) return;
        if (existing != null && existing.getStatus() == MemberStatus.RESTRICTED) {
            throw new ForbiddenException(
                    ChannelStreamMessages.DISCUSSION_RESTRICTED_MSG, ChannelStreamMessages.READ_ONLY);
        }
        if (existing != null) {
            existing.setStatus(MemberStatus.ACTIVE);
            existing.setRole(MemberRole.MEMBER);
            memberRepo.save(existing);
        } else {
            memberRepo.save(ConversationMember.of(group, userId, MemberRole.MEMBER, "COMMENT"));
        }
        conversationRepo.adjustMemberCount(group.getId(), +1);
    }

    private void requirePostOf(UUID channelId, long postId) {
        MessageByIdEntity post = messageByIdRepo.findById(postId)
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));
        if (!channelId.equals(post.getConversationId())) {
            throw new BadRequestException(ChannelStreamMessages.NOT_CHANNEL_POST_MSG);
        }
    }

    private Conversation requireChannel(UUID id) {
        return conversationRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null && c.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", id));
    }

    private void requireSubscriber(UUID channelId, UUID userId) {
        memberRepo.findMember(channelId, userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException(
                        ChannelStreamMessages.NOT_CHANNEL_MEMBER_MSG, ChannelStreamMessages.NOT_A_MEMBER));
    }

    private void requireChangeInfo(UUID channelId, UUID actorId) {
        ConversationMember actor = memberRepo.findMember(channelId, actorId).orElse(null);
        if (!ChannelRights.can(actor, AdminRights::isCanChangeInfo)) {
            throw new ForbiddenException(
                    ChannelStreamMessages.DISCUSSION_MANAGE_FORBIDDEN_MSG, ChannelStreamMessages.ADMINS_ONLY);
        }
    }
}
