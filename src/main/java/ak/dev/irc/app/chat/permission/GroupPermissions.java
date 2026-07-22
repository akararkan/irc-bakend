package ak.dev.irc.app.chat.permission;

import ak.dev.irc.app.chat.dto.GroupSettings;
import ak.dev.irc.app.chat.enums.GroupAction;
import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.enums.MemberScope;

/**
 * The single, pure authority function for GROUP actions — the encoded
 * permission matrix from the design. Every controller/service funnels role
 * decisions through {@link #can} so role logic is never scattered or drifts.
 *
 * <p>Key invariant: <b>an admin can never act on the owner or on another
 * admin</b> — only on plain members. The owner can act on anyone.</p>
 */
public final class GroupPermissions {

    private GroupPermissions() {}

    /**
     * @param actor    the acting member's role (never null)
     * @param action   the action attempted
     * @param target   the target member's role, or {@code null} for actions with no target
     * @param settings the group settings (may be null → defaults)
     */
    public static boolean can(MemberRole actor, GroupAction action, MemberRole target, GroupSettings settings) {
        GroupSettings s = settings != null ? settings : GroupSettings.defaults();
        boolean actorIsOwner = actor == MemberRole.OWNER;
        boolean actorIsAdmin = actor == MemberRole.ADMIN;
        boolean actorIsStaff = actorIsOwner || actorIsAdmin;
        boolean targetIsPlainMember = target == MemberRole.MEMBER;

        return switch (action) {
            case SEND_MESSAGE ->
                    s.getSendMode() != MemberScope.ADMINS_ONLY || actorIsStaff;

            case ADD_MEMBERS ->
                    actorIsStaff || s.getWhoCanAddMembers() == MemberScope.ALL_MEMBERS;

            case EDIT_INFO ->
                    actorIsStaff || s.getWhoCanEditInfo() == MemberScope.ALL_MEMBERS;

            case PIN_MESSAGE ->
                    actorIsStaff || s.getWhoCanPin() == MemberScope.ALL_MEMBERS;

            // Admin may act on plain members only; owner on anyone.
            case REMOVE_MEMBER, RESTRICT_MEMBER ->
                    actorIsOwner || (actorIsAdmin && targetIsPlainMember);

            case PROMOTE_ADMIN ->
                    actorIsOwner || (actorIsAdmin && s.isAdminsCanPromote() && targetIsPlainMember);

            case CHANGE_SETTINGS, DELETE_ANY_MESSAGE, CREATE_INVITE ->
                    actorIsStaff;

            case DEMOTE_ADMIN, TRANSFER_OWNERSHIP, DELETE_GROUP ->
                    actorIsOwner;
        };
    }
}
