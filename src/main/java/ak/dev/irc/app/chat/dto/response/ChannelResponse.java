package ak.dev.irc.app.chat.dto.response;

import ak.dev.irc.app.chat.dto.ChannelSettings;

import java.time.LocalDateTime;
import java.util.UUID;

/** A channel's directory/detail view. Posting to and reading a channel use the
 *  normal conversation/message endpoints with this {@code id}. */
public record ChannelResponse(
        UUID id,
        String handle,
        String title,
        String description,
        String category,
        boolean publicChannel,
        boolean verified,
        long subscriberCount,
        long postCount,
        UUID ownerId,
        boolean subscribed,
        /** The viewer filed a join request that is still awaiting approval. */
        boolean pendingJoinRequest,
        /** Viewer's role (OWNER/ADMIN/MEMBER), null when not a member. */
        String myRole,
        String avatarUrl,
        String coverUrl,
        /** Reactions policy, protected content, signatures, join-by-request, … */
        ChannelSettings settings,
        /** The linked discussion group where subscribers comment (null = off). */
        UUID linkedGroupId,
        LocalDateTime createdAt,
        /** Public share link ({@code {base}/c/{handle}}); {@code null} for a
         *  private channel — share those via an invite link instead. */
        String shareUrl
) {}
