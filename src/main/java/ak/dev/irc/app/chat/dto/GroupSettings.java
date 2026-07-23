package ak.dev.irc.app.chat.dto;

import ak.dev.irc.app.chat.enums.MemberScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Group configuration, persisted as a single {@code jsonb} column on the
 * conversation so new knobs can be added without a migration.
 *
 * <p>Mutable Lombok bean (not a record) because Hibernate's JSON mapper and
 * Jackson both round-trip it by field, and partial {@code PATCH} updates set
 * individual knobs.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupSettings {

    /** ALL_MEMBERS = everyone can post; ADMINS_ONLY = broadcast-style. */
    @Builder.Default
    private MemberScope sendMode = MemberScope.ALL_MEMBERS;

    @Builder.Default
    private MemberScope whoCanAddMembers = MemberScope.ALL_MEMBERS;

    @Builder.Default
    private MemberScope whoCanEditInfo = MemberScope.ADMINS_ONLY;

    @Builder.Default
    private MemberScope whoCanPin = MemberScope.ADMINS_ONLY;

    /** When true, admins (not only the owner) may promote members to admin. */
    @Builder.Default
    private boolean adminsCanPromote = false;

    /** When true, new joiners see messages sent before they joined. */
    @Builder.Default
    private boolean historyVisibleToNewMembers = true;

    /** Telegram slow mode — non-admin members may send at most one message per
     *  this many seconds (0 = off). Typical for channel discussion groups. */
    @Builder.Default
    private int slowModeSeconds = 0;

    /** Sensible defaults for a freshly created group. */
    public static GroupSettings defaults() {
        return GroupSettings.builder().build();
    }
}
