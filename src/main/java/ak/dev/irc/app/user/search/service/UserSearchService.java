package ak.dev.irc.app.user.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.ReindexSummary;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.search.document.UserSearchDocument;
import ak.dev.irc.app.user.search.repository.UserSearchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch-backed indexer for the {@code irc-users} people index.
 *
 * Index-only: query-time search is served by the unified
 * {@code /api/v1/search?types=USER} endpoint ({@code GlobalSearchService}).
 * The dedicated Postgres people search at {@code /api/v1/users/search}
 * stays untouched — this index only feeds the global search bar.
 *
 * <p>{@link #indexAsync(UUID)} takes an id, not an entity: the background
 * thread re-loads user + profile itself (join-fetched), so callers never
 * hand a lazy JPA proxy across threads. Because every caller runs inside a
 * {@code @Transactional} write, the hand-off is deferred to
 * <em>after commit</em> — otherwise the background read could race the
 * commit and miss the row entirely. Best-effort — a lost write only means
 * the user is temporarily missing from global search; Postgres stays
 * canonical and the admin reindex heals drift.</p>
 */
@Slf4j
@Service
public class UserSearchService {

    private final UserSearchRepository   searchRepo;
    private final UserRepository         userRepo;
    private final UserFollowRepository   followRepo;
    private final ElasticsearchOperations esOps;
    private final Executor               executor;

    public UserSearchService(UserSearchRepository searchRepo,
                             UserRepository userRepo,
                             UserFollowRepository followRepo,
                             ElasticsearchOperations esOps,
                             @Qualifier("taskExecutor") Executor executor) {
        this.searchRepo = searchRepo;
        this.userRepo   = userRepo;
        this.followRepo = followRepo;
        this.esOps      = esOps;
        this.executor   = executor;
    }

    /**
     * (Re-)index one user. Runs on the shared task executor after the
     * surrounding transaction commits (immediately if there is none); a
     * user that is soft-deleted / disabled by the time we look is removed
     * from the index instead.
     */
    public void indexAsync(UUID userId) {
        if (userId == null) return;
        Runnable work = () -> doIndex(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { executor.execute(work); }
            });
        } else {
            executor.execute(work);
        }
    }

    /**
     * The background body of {@link #indexAsync}. Repository calls open
     * their own short transactions; the profile arrives join-fetched, so
     * no lazy proxy outlives its session.
     */
    private void doIndex(UUID userId) {
        try {
            List<User> found = userRepo.findActiveWithProfileByIdIn(List.of(userId));
            if (found.isEmpty()) {
                EsRetry.run(() -> searchRepo.deleteById(userId.toString()),
                        "[SEARCH] delete (inactive) user " + userId);
                return;
            }
            User user = found.get(0);
            long followers = followRepo.countByFollowingId(userId);
            EsRetry.run(() -> searchRepo.save(buildDoc(user, followers)),
                    "[SEARCH] index user " + userId);
        } catch (Exception e) {
            log.warn("[SEARCH] index user {} failed: {}", userId, e.getMessage());
        }
    }

    /** Remove a user from the index — call from the account-deletion path. */
    @Async
    public void deleteAsync(UUID userId) {
        if (userId == null) return;
        try {
            EsRetry.run(() -> searchRepo.deleteById(userId.toString()),
                    "[SEARCH] delete user " + userId);
        } catch (Exception e) {
            log.warn("[SEARCH] delete user {} failed: {}", userId, e.getMessage());
        }
    }

    /**
     * One-shot reindex of every active account into {@code irc-users}.
     * Synchronous — the admin endpoint waits so the response carries counts.
     */
    @Transactional(readOnly = true)
    public ReindexSummary reindexAll(boolean dropFirst) {
        Instant start = Instant.now();
        boolean dropped = false;
        if (dropFirst) {
            try {
                EsRetry.run(() -> esOps.indexOps(UserSearchDocument.class).delete(),
                        "[SEARCH] drop irc-users");
                dropped = true;
            } catch (Exception e) {
                log.warn("[SEARCH] irc-users drop best-effort failed (often harmless): {}", e.getMessage());
            }
            // Recreate from the annotated mapping immediately — relying on the
            // first save() would dynamic-map every Keyword field back to text.
            try {
                EsRetry.run(() -> esOps.indexOps(UserSearchDocument.class).createWithMapping(),
                        "[SEARCH] recreate irc-users mapping");
            } catch (Exception e) {
                log.warn("[SEARCH] irc-users mapping recreate failed: {}", e.getMessage());
            }
        }

        final int PAGE_SIZE = 100;
        long indexed = 0;
        int pages = 0, page = 0;
        while (true) {
            Page<User> slice = userRepo.findAllActive(PageRequest.of(page, PAGE_SIZE));
            if (slice.isEmpty()) break;

            List<UserSearchDocument> docs = new ArrayList<>(slice.getNumberOfElements());
            for (User u : slice.getContent()) {
                docs.add(buildDoc(u, followRepo.countByFollowingId(u.getId())));
            }
            try {
                EsRetry.run(() -> searchRepo.saveAll(docs),
                        "[SEARCH] reindex users page " + page);
                indexed += docs.size();
                pages++;
            } catch (Exception e) {
                log.warn("[SEARCH] user reindex page {} failed ({} docs skipped): {}",
                        page, docs.size(), e.getMessage());
            }
            if (!slice.hasNext()) break;
            page++;
        }

        long ms = Duration.between(start, Instant.now()).toMillis();
        String note = String.format("Reindexed %d active user(s) across %d page(s)%s.",
                indexed, pages, dropped ? " (index was dropped first)" : "");
        log.info("[SEARCH] {} ({}ms)", note, ms);
        return new ReindexSummary(dropped, indexed, pages, ms, note);
    }

    /** "ahmad.rashid_92" → "ahmad rashid 92" so each segment is independently matchable. */
    private static String splitHandle(String handle) {
        if (handle == null || handle.isBlank()) return null;
        return handle.replaceAll("[._\\-]+", " ").trim();
    }

    private static UserSearchDocument buildDoc(User user, long followerCount) {
        UserProfile p = user.getProfile();
        return UserSearchDocument.builder()
                .id(UserSearchDocument.idOf(user.getId()))
                .username(user.getUsername())
                .usernameTokens(splitHandle(user.getUsername()))
                .fname(user.getFname())
                .lname(user.getLname())
                .displayName(p == null ? null : p.getDisplayName())
                .bio(p == null ? null : p.getProfileBio())
                .academicTitle(p == null ? null : p.getAcademicTitle())
                .institutionName(p == null ? null : p.getInstitutionName())
                .role(user.getRole() == null ? null : user.getRole().name())
                .status("ACTIVE")
                .followerCount(followerCount)
                .createdAt(user.getCreatedAt() == null ? null
                        : user.getCreatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }
}
