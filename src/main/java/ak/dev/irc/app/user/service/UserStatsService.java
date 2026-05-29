package ak.dev.irc.app.user.service;

import ak.dev.irc.app.post.cassandra.repository.PostByAuthorRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.repository.ResearchRepository;
import ak.dev.irc.app.user.dto.response.UserStatsResponse;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Computes a user's profile-stat counts live from each source of truth, rather
 * than from the denormalized {@code user_profiles} counters (which are not kept
 * in sync and read 0). Computing on demand keeps the hot {@code /users/me} and
 * {@code /users/{id}} fetches cheap — the stat row pays for itself only when shown.
 *
 * <p>Each count is isolated: if one store is briefly unavailable (e.g. Cassandra
 * for posts) that number degrades to 0 instead of failing the whole row.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserStatsService {

    private final UserFollowRepository   followRepo;
    private final ResearchRepository     researchRepo;
    private final QuestionRepository     questionRepo;
    private final PostByAuthorRepository postByAuthorRepo;

    public UserStatsResponse statsFor(UUID userId) {
        long reels    = safe("reels", () -> postByAuthorRepo.countReelsByAuthor(userId));
        long allPosts = safe("posts", () -> postByAuthorRepo.countByAuthor(userId));
        long posts    = Math.max(0L, allPosts - reels);   // posts exclude reels
        return new UserStatsResponse(
                posts,
                reels,
                safe("research",  () -> researchRepo.countByResearcherIdAndStatusAndDeletedAtIsNull(
                        userId, ResearchStatus.PUBLISHED)),
                safe("questions", () -> questionRepo.countByAuthorIdAndDeletedAtIsNull(userId)),
                safe("followers", () -> followRepo.countByFollowingId(userId)),
                safe("following", () -> followRepo.countByFollowerId(userId))
        );
    }

    private long safe(String label, LongSupplier count) {
        try {
            return count.getAsLong();
        } catch (Exception e) {
            log.warn("[USER-STATS] {} count failed: {}", label, e.getMessage());
            return 0L;
        }
    }
}
