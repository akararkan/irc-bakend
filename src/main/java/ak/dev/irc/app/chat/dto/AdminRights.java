package ak.dev.irc.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Telegram-style granular admin rights, persisted as a {@code jsonb} column on
 * the membership row. A {@code null} column (legacy admins, and the owner) means
 * <b>full rights</b> — every flag defaults to {@code true}, so promoting an
 * admin without specifying rights grants everything, and rights only ever
 * <i>remove</i> capability.
 *
 * <p>{@code ignoreUnknown = true} keeps reads of already-persisted rows tolerant
 * of retired flags — notably {@code canManageStories}, dropped when channel
 * stories were removed; stale JSON that still carries it deserializes cleanly.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminRights {

    /** Post new messages to the channel. */
    @Builder.Default private boolean canPostMessages = true;

    /** Edit any post (not only their own). */
    @Builder.Default private boolean canEditMessages = true;

    /** Delete any post. */
    @Builder.Default private boolean canDeleteMessages = true;

    /** Pin / unpin posts. */
    @Builder.Default private boolean canPinMessages = true;

    /** Create and revoke invite links, add members. */
    @Builder.Default private boolean canInviteUsers = true;

    /** Approve / reject join requests. */
    @Builder.Default private boolean canApproveJoinRequests = true;

    /** Edit the channel's title, description, photo, cover and settings. */
    @Builder.Default private boolean canChangeInfo = true;

    /** Promote members to admin / edit other admins' rights. */
    @Builder.Default private boolean canAddAdmins = true;

    /** Start / manage live streams and video chats. */
    @Builder.Default private boolean canManageLive = true;

    /** Custom rank label shown next to the admin's name (e.g. "Editor"). */
    private String customTitle;

    /** Everything granted — what a bare promotion means. */
    public static AdminRights full() {
        return AdminRights.builder().build();
    }
}
