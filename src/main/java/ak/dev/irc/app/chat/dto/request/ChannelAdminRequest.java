package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Promote a member to channel admin / edit an admin's rights. Null flags default
 * to {@code true} (a bare promotion grants full rights; flags only remove).
 */
@Data
public class ChannelAdminRequest {

    private Boolean canPostMessages;
    private Boolean canEditMessages;
    private Boolean canDeleteMessages;
    private Boolean canPinMessages;
    private Boolean canInviteUsers;
    private Boolean canApproveJoinRequests;
    private Boolean canChangeInfo;
    private Boolean canAddAdmins;
    private Boolean canManageLive;
    private Boolean canManageStories;

    /** Custom rank label (e.g. "Editor"). */
    @Size(max = 32)
    private String customTitle;
}
