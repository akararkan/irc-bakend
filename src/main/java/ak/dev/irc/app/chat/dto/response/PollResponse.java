package ak.dev.irc.app.chat.dto.response;

import java.util.List;

/**
 * A poll rendered for a viewer. Quiz answers ({@code correctOptionIndex},
 * {@code explanation}) are only revealed once the viewer has voted or the poll
 * is closed.
 */
public record PollResponse(
        String question,
        List<PollOption> options,
        boolean allowsMultipleAnswers,
        boolean anonymous,
        boolean quiz,
        Integer correctOptionIndex,
        String explanation,
        boolean closed,
        long totalVoters,
        /** Option indexes the viewer picked (empty if they haven't voted). */
        List<Integer> myVotes
) {
    public record PollOption(int index, String text, long voterCount) {}
}
