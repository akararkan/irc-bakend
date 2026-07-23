package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.AdminRights;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.permission.ChannelRights;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.cassandra.entity.HighlightByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryInHighlightEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraHighlightService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * Channel story highlights — permanent curated archives of channel stories on
 * the channel profile, exactly like user highlights. Reuses the highlight
 * infrastructure with the channel's conversation id as the "author" partition:
 * snapshots survive the story TTL, ordering is the profile-rail order, and the
 * underlying service's author checks compare against the channel id.
 *
 * <p>Curated by admins holding {@code canManageStories}; visible to the
 * channel's audience (everyone for public channels, subscribers for private).</p>
 */
@Service
@RequiredArgsConstructor
public class ChannelHighlightService {

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final CassandraHighlightService highlightService;

    // ── Curate (staff) ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public HighlightByAuthorEntity create(UUID channelId, UUID actorId, String title, String coverUrl) {
        requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        if (!StringUtils.hasText(title)) throw new BadRequestException("A highlight requires a title.");
        int nextOrder = highlightService.listFor(channelId).size();
        return highlightService.createHighlight(channelId, title.trim(), coverUrl, nextOrder);
    }

    @Transactional(readOnly = true)
    public StoryInHighlightEntity addStory(UUID channelId, UUID highlightId, UUID storyId, UUID actorId) {
        requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        requireOwnedHighlight(channelId, highlightId);
        // The underlying author/ownership checks compare against the story's and
        // highlight's author partition — both ARE the channel id here.
        return highlightService.addStoryToHighlight(highlightId, storyId, channelId)
                .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
    }

    /** Remove a snapshot; {@code createdAt} is resolved server-side. */
    @Transactional(readOnly = true)
    public void removeStory(UUID channelId, UUID highlightId, UUID storyId, UUID actorId) {
        requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        requireOwnedHighlight(channelId, highlightId);
        StoryInHighlightEntity row = highlightService.storiesIn(highlightId).stream()
                .filter(s -> storyId.equals(s.getStoryId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        highlightService.removeStoryFromHighlight(highlightId, row.getCreatedAt(), storyId, channelId);
    }

    @Transactional(readOnly = true)
    public void deleteHighlight(UUID channelId, UUID highlightId, UUID actorId) {
        requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        HighlightByAuthorEntity h = requireOwnedHighlight(channelId, highlightId);
        highlightService.deleteHighlight(h);
    }

    @Transactional(readOnly = true)
    public List<HighlightByAuthorEntity> reorder(UUID channelId, UUID actorId, List<UUID> order) {
        requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        if (order == null || order.isEmpty()) throw new BadRequestException("order list is required.");
        return highlightService.reorderHighlights(channelId, order);
    }

    // ── Read (audience) ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<HighlightByAuthorEntity> list(UUID channelId, UUID viewerId) {
        requireAudience(channelId, viewerId);
        return highlightService.listFor(channelId);
    }

    @Transactional(readOnly = true)
    public List<StoryInHighlightEntity> storiesIn(UUID channelId, UUID highlightId, UUID viewerId) {
        requireAudience(channelId, viewerId);
        requireOwnedHighlight(channelId, highlightId);
        return highlightService.storiesIn(highlightId);
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private HighlightByAuthorEntity requireOwnedHighlight(UUID channelId, UUID highlightId) {
        return highlightService.listFor(channelId).stream()
                .filter(h -> h.getHighlightId().equals(highlightId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Highlight", "id", highlightId));
    }

    private Conversation requireChannel(UUID id) {
        return conversationRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null && c.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", id));
    }

    /** Public channel → anyone; private → active/readable subscribers. */
    private void requireAudience(UUID channelId, UUID viewerId) {
        Conversation channel = requireChannel(channelId);
        if (channel.isPublicChannel()) return;
        memberRepo.findMember(channelId, viewerId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException(
                        "You are not a member of this channel.", "NOT_A_MEMBER"));
    }

    private void requireStoryManager(UUID channelId, UUID actorId) {
        ConversationMember actor = memberRepo.findMember(channelId, actorId).orElse(null);
        if (!ChannelRights.can(actor, AdminRights::isCanManageStories)) {
            throw new ForbiddenException("You cannot manage this channel's highlights.", "ADMINS_ONLY");
        }
    }
}
