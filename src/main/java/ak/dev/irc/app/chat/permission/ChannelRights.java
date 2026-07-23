package ak.dev.irc.app.chat.permission;

import ak.dev.irc.app.chat.dto.AdminRights;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.MemberRole;

import java.util.function.Predicate;

/**
 * The single authority check for granular CHANNEL admin rights, layered on top
 * of {@link GroupPermissions}: the owner can do everything; an admin can do what
 * their {@link AdminRights} allow ({@code null} rights = legacy admin = full);
 * a plain member can do none of it.
 */
public final class ChannelRights {

    private ChannelRights() {}

    public static boolean can(ConversationMember member, Predicate<AdminRights> right) {
        if (member == null || !member.isActive()) return false;
        if (member.isOwner()) return true;
        if (member.getRole() != MemberRole.ADMIN) return false;
        AdminRights r = member.getAdminRights();
        return r == null || right.test(r);
    }
}
