package ak.dev.irc.app.chat.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * A live stream's full <b>stage roster</b> — everyone currently up (the host first,
 * then the active guests in stage order) plus the guest capacity. This is the
 * payload of every {@code stream.stage} broadcast and of {@code GET
 * /streams/{id}/stage}; a client renders the whole panel straight from it and
 * knows when the stage is full ({@code guestCount >= maxGuests}).
 *
 * <p>The {@code members} here are the <b>public</b> view — secret publish
 * credentials are stripped (see {@link StageMember}).</p>
 */
public record StageState(
        UUID streamId,
        UUID hostId,
        /** Host + active guests, host first. */
        List<StageMember> members,
        /** Active guests only (excludes the host). */
        int guestCount,
        /** How many guests may be up at once. */
        int maxGuests
) {}
