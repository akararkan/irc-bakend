package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.post.cassandra.entity.FriendSuggestionEntity;
import ak.dev.irc.app.post.cassandra.entity.UserAuthorAffinityEntity;
import ak.dev.irc.app.post.cassandra.repository.FriendSuggestionRepository;
import ak.dev.irc.app.post.cassandra.repository.UserAuthorAffinityRepository;
import ak.dev.irc.app.post.dto.AuthorSummary;
import ak.dev.irc.app.post.dto.SuggestionResponse;
import ak.dev.irc.app.user.entity.SuggestionDismissal;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.entity.UserTopicSpecialization;
import ak.dev.irc.app.user.repository.SuggestionDismissalRepository;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserProfileRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import ak.dev.irc.app.user.repository.UserRestrictionRepository;
import ak.dev.irc.app.user.service.ContactMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-signal friend-suggestion engine — "People You May Know".
 *
 * <p>Follows the standard multi-stage pipeline (candidate generation →
 * signal collection → scoring → privacy/safety filtering → diversity →
 * delivery). The old engine used exactly one signal (friends-of-friends
 * mutual count); this one combines the platform's whole relationship
 * surface:</p>
 *
 * <h3>Candidate sources (Stage 1)</h3>
 * <ul>
 *   <li><b>GRAPH</b> — friends-of-friends walk over the follow graph
 *       (mutual-follow tally, the classic strongest predictor);</li>
 *   <li><b>CONTACTS</b> — hashed contact matches via
 *       {@link ContactMatchService} (best cold-start signal; bidirectional
 *       matches — both users have each other saved — score extra);</li>
 *   <li><b>MESSAGING</b> — people the user has DIRECT threads with but
 *       doesn't follow (communication history strongly predicts
 *       connections);</li>
 *   <li><b>GROUPS</b> — co-members of shared GROUP conversations, weighted
 *       by how many groups are shared;</li>
 *   <li><b>INTERACTIONS</b> — authors the user engages with
 *       ({@code user_author_affinity} interest graph) without following;</li>
 *   <li><b>AFFILIATION</b> — same institution (workplace/university
 *       matching for an academic network).</li>
 * </ul>
 *
 * <h3>Scoring (Stage 2–3)</h3>
 * Weighted sum of: mutual count, contact match (+bidirectional bonus),
 * DM relationship, shared groups, engagement affinity (log-damped),
 * institution / location / specialization / language overlap, and profile
 * quality (academic badge, completed profile). Weights are the
 * {@code W_*} constants — documented in docs/suggestions/algorithm.md.
 *
 * <h3>Privacy & safety filter (Stage 4)</h3>
 * Removed outright: self, already-followed, blocked (either direction),
 * restricted (either direction), dismissed suggestions (explicit negative
 * feedback), deleted accounts, and locked profiles (they can't be followed
 * anyway — see {@code UserSocialServiceImpl.follow}).
 *
 * <h3>Diversity (Stage 5)</h3>
 * The stored top-N is score-ordered, but every candidate source with at
 * least one surviving candidate is guaranteed representation so the list
 * isn't wall-to-wall one signal.
 *
 * <p>Read path is unchanged: a single Cassandra partition scan of
 * {@code friend_suggestions_by_user} (score DESC at the table level).
 * Recompute stays async — triggered on demand, after contact sync, and on
 * follow/unfollow.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendSuggestionService {

    // ── Bounds ───────────────────────────────────────────────────────────────
    private static final int MAX_SUGGESTIONS_TO_STORE = 50;
    private static final int GRAPH_FRIEND_SCAN_CAP    = 400;  // FoF walk: max friends expanded
    private static final int CANDIDATE_CAP            = 300;  // max candidates fully scored
    private static final int GROUP_CO_MEMBER_CAP      = 200;
    private static final int AFFINITY_SCAN_CAP        = 500;
    private static final int INSTITUTION_PEER_CAP     = 50;
    private static final int DIVERSITY_HEAD           = 20;   // sources guaranteed in the head
    private static final double MIN_SCORE             = 2.0;

    // ── Signal weights (see docs/suggestions/algorithm.md) ───────────────────
    static final double W_MUTUAL          = 3.0;   // per mutual follow, capped
    static final int    MUTUAL_CAP        = 15;
    static final double W_CONTACT         = 12.0;  // strongest single signal
    static final double W_CONTACT_BIDIR   = 6.0;   // extra when both saved each other
    static final double W_DM              = 10.0;  // existing conversation
    static final double W_GROUP           = 2.5;   // per shared group, capped
    static final int    GROUP_CAP         = 4;
    static final double W_AFFINITY        = 1.5;   // × ln(1 + engagement counter)
    static final double W_INSTITUTION     = 4.0;
    static final double W_LOCATION        = 2.5;
    static final double W_SPECIALIZATION  = 1.5;   // per shared topic, capped
    static final int    SPECIALIZATION_CAP = 3;
    static final double W_LANGUAGE        = 0.5;
    static final double W_BADGE           = 0.75;  // RESEARCHER / SCHOLAR / ADMIN
    static final double W_COMPLETE        = 0.75;  // bio + avatar present

    /**
     * Read-only knob registry for the admin observability surface
     * (discovery-pymk-privacy.md §5.1): the 6 sources, the weight constants
     * and the gates — each explicitly "recompile-only".
     */
    public static java.util.Map<String, Object> knobRegistry() {
        java.util.Map<String, Object> knobs = new java.util.LinkedHashMap<>();
        knobs.put("sources", java.util.List.of(
                "GRAPH", "CONTACTS", "MESSAGING", "GROUPS", "INTERACTIONS", "AFFILIATION"));
        knobs.put("W_MUTUAL", W_MUTUAL);
        knobs.put("MUTUAL_CAP", MUTUAL_CAP);
        knobs.put("W_CONTACT", W_CONTACT);
        knobs.put("W_CONTACT_BIDIR", W_CONTACT_BIDIR);
        knobs.put("W_DM", W_DM);
        knobs.put("W_GROUP", W_GROUP);
        knobs.put("GROUP_CAP", GROUP_CAP);
        knobs.put("W_AFFINITY", W_AFFINITY);
        knobs.put("W_INSTITUTION", W_INSTITUTION);
        knobs.put("W_LOCATION", W_LOCATION);
        knobs.put("W_SPECIALIZATION", W_SPECIALIZATION);
        knobs.put("SPECIALIZATION_CAP", SPECIALIZATION_CAP);
        knobs.put("W_LANGUAGE", W_LANGUAGE);
        knobs.put("W_BADGE", W_BADGE);
        knobs.put("W_COMPLETE", W_COMPLETE);
        knobs.put("MIN_SCORE", MIN_SCORE);
        knobs.put("MAX_SUGGESTIONS_TO_STORE", MAX_SUGGESTIONS_TO_STORE);
        knobs.put("DIVERSITY_HEAD", DIVERSITY_HEAD);
        knobs.put("CANDIDATE_CAP", CANDIDATE_CAP);
        knobs.put("tuning", "recompile-only — no runtime config surface exists by design");
        return knobs;
    }

    private final UserFollowRepository          userFollowRepo;
    private final FriendSuggestionRepository    suggestionRepo;
    private final UserBlockRepository           blockRepo;
    private final UserRestrictionRepository     restrictionRepo;
    private final SuggestionDismissalRepository dismissalRepo;
    private final ContactMatchService           contactMatchService;
    private final ConversationMemberRepository  conversationMemberRepo;
    private final UserAuthorAffinityRepository  affinityRepo;
    private final UserRepository                userRepo;
    private final UserProfileRepository         profileRepo;

    // ── Read paths ───────────────────────────────────────────────────────────

    /** Fast raw read — already sorted by score DESC in the partition. */
    public List<FriendSuggestionEntity> topSuggestionsFor(UUID userId, int limit) {
        return suggestionRepo.topSuggestions(userId, limit);
    }

    /**
     * Hydrated read: candidate identity (username, name, avatar — profiles
     * join-fetched so avatars actually populate) + score + reason. Rows
     * whose candidate has since been deleted are dropped.
     */
    public List<SuggestionResponse> detailedSuggestionsFor(UUID userId, int limit) {
        List<FriendSuggestionEntity> rows = suggestionRepo.topSuggestions(userId, limit);
        if (rows.isEmpty()) return List.of();

        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> { if (r.getCandidateId() != null) ids.add(r.getCandidateId()); });
        Map<UUID, User> users = new HashMap<>(ids.size());
        userRepo.findActiveWithProfileByIdIn(ids).forEach(u -> users.put(u.getId(), u));

        List<SuggestionResponse> out = new ArrayList<>(rows.size());
        for (FriendSuggestionEntity r : rows) {
            User u = users.get(r.getCandidateId());
            if (u == null) continue;
            out.add(new SuggestionResponse(
                    u.getId(),
                    new AuthorSummary(u.getId(), u.getUsername(), u.getFullName(), u.getProfileImage()),
                    storedToScore(r.getScore()),
                    r.getReason(),
                    r.getComputedAt()));
        }
        return out;
    }

    /**
     * Explicit negative feedback: remove the suggestion now and exclude the
     * candidate from every future recompute.
     */
    public void dismiss(UUID userId, UUID candidateId) {
        try {
            dismissalRepo.save(SuggestionDismissal.builder()
                    .id(new SuggestionDismissal.Key(userId, candidateId))
                    .dismissedAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.debug("[SUGGEST] dismissal persist for {}→{}: {}", userId, candidateId, e.getMessage());
        }
        // Cassandra needs the full PK (incl. score) to delete — find the row first.
        for (FriendSuggestionEntity row : suggestionRepo.topSuggestions(userId, MAX_SUGGESTIONS_TO_STORE * 2)) {
            if (candidateId.equals(row.getCandidateId()) && row.getScore() != null) {
                suggestionRepo.deleteSuggestion(userId, row.getScore(), candidateId);
            }
        }
    }

    // ── Recompute pipeline ───────────────────────────────────────────────────

    /**
     * Recompute suggestions for one user. Async — fire from controllers /
     * social mutations / contact sync; never blocks the request.
     */
    @Async
    public void recomputeFor(UUID userId) {
        long start = System.currentTimeMillis();
        try {
            // ═ Stage 1 — candidate generation ═
            Set<UUID> following = new HashSet<>(userFollowRepo.findFollowingIds(userId));

            Map<UUID, Integer> mutualCounts   = friendsOfFriends(userId, following);
            Set<UUID>          contactMatches = contactMatchService.matchedContactIds(userId);
            Set<UUID>          bidirContacts  = contactMatches.isEmpty()
                    ? Set.of() : contactMatchService.bidirectionalMatchIds(userId);
            Set<UUID>          dmPeers        = directPeers(userId);
            Map<UUID, Long>    sharedGroups   = groupCoMembers(userId);
            Map<UUID, Long>    affinity       = engagedAuthors(userId);
            UserProfile        myProfile      = profileRepo.findByUserIdWithSpecializations(userId).orElse(null);
            Set<UUID>          colleagues     = institutionPeers(userId, myProfile);

            Set<UUID> candidates = new HashSet<>();
            candidates.addAll(mutualCounts.keySet());
            candidates.addAll(contactMatches);
            candidates.addAll(dmPeers);
            candidates.addAll(sharedGroups.keySet());
            candidates.addAll(affinity.keySet());
            candidates.addAll(colleagues);
            candidates.remove(userId);
            candidates.removeAll(following);

            if (candidates.isEmpty()) {
                suggestionRepo.clearForUser(userId);
                return;
            }

            // ═ Stage 4 first where it prunes cheaply — privacy & negative signals ═
            candidates.removeAll(new HashSet<>(dismissalRepo.findDismissedCandidateIds(userId)));
            if (!candidates.isEmpty())
                candidates.removeAll(new HashSet<>(blockRepo.findBlockedAmong(userId, candidates)));
            if (!candidates.isEmpty())
                candidates.removeAll(new HashSet<>(restrictionRepo.findRestrictedAmong(userId, candidates)));
            if (candidates.isEmpty()) {
                suggestionRepo.clearForUser(userId);
                return;
            }

            // Bound the fully-scored set: strong-signal candidates always kept,
            // long FoF tail trimmed by mutual count.
            if (candidates.size() > CANDIDATE_CAP) {
                candidates = capCandidates(candidates, mutualCounts, contactMatches, dmPeers,
                                           sharedGroups.keySet());
            }

            // ═ Stage 2 — bulk relationship/profile signal loads ═
            Map<UUID, User> users = new HashMap<>(candidates.size());
            userRepo.findActiveWithProfileByIdIn(candidates)
                    .forEach(u -> users.put(u.getId(), u));  // deleted accounts drop here
            Map<UUID, UserProfile> profiles = new HashMap<>(candidates.size());
            // Key by USER id — UserProfile.id is the profile's own generated
            // UUID, not the user's. Proxy id access on the lazy user is safe.
            profileRepo.findAllByUserIdInWithSpecializations(candidates)
                    .forEach(p -> profiles.put(p.getUser().getId(), p));

            String  myInstitution = myProfile == null ? null : normalize(myProfile.getInstitutionName());
            String  myLocation    = myProfile == null ? null : normalize(myProfile.getLocation());
            Object  myLanguage    = myProfile == null ? null : myProfile.getContentLanguage();
            Set<Integer> myTopics = topicIds(myProfile);

            // ═ Stage 3 — score ═
            List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
            for (UUID candidateId : candidates) {
                User candidate = users.get(candidateId);
                if (candidate == null) continue;                    // deleted
                if (candidate.isProfileLocked()) continue;          // unfollowable → don't suggest

                ScoredCandidate sc = score(candidateId, candidate, profiles.get(candidateId),
                        mutualCounts.getOrDefault(candidateId, 0),
                        contactMatches.contains(candidateId),
                        bidirContacts.contains(candidateId),
                        dmPeers.contains(candidateId),
                        sharedGroups.getOrDefault(candidateId, 0L),
                        affinity.getOrDefault(candidateId, 0L),
                        myInstitution, myLocation, myLanguage, myTopics);
                if (sc.score >= MIN_SCORE) scored.add(sc);
            }

            scored.sort((a, b) -> Double.compare(b.score, a.score));

            // ═ Stage 5 — diversity + persist ═
            List<ScoredCandidate> finals = diversify(scored);
            suggestionRepo.clearForUser(userId);
            Instant now = Instant.now();
            for (ScoredCandidate sc : finals) {
                suggestionRepo.save(FriendSuggestionEntity.builder()
                        .userId(userId)
                        .score(scoreToStored(sc.score))
                        .candidateId(sc.candidateId)
                        .reason(sc.reason)
                        .computedAt(now)
                        .build());
            }
            log.info("[SUGGEST] user {} → {} suggestions ({} candidates) in {} ms",
                    userId, finals.size(), candidates.size(), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.warn("[SUGGEST] recompute for user {} failed: {}", userId, ex.getMessage());
        }
    }

    // ── Candidate sources ────────────────────────────────────────────────────

    /** Classic FoF walk: tally how many of my follows also follow each candidate. */
    private Map<UUID, Integer> friendsOfFriends(UUID userId, Set<UUID> following) {
        Map<UUID, Integer> mutualCounts = new HashMap<>();
        int expanded = 0;
        for (UUID friend : following) {
            if (++expanded > GRAPH_FRIEND_SCAN_CAP) break;
            List<UUID> friendsOfFriend;
            try {
                friendsOfFriend = userFollowRepo.findFollowingIds(friend);
            } catch (Exception e) { continue; }
            for (UUID candidate : friendsOfFriend) {
                if (candidate.equals(userId) || following.contains(candidate)) continue;
                mutualCounts.merge(candidate, 1, Integer::sum);
            }
        }
        return mutualCounts;
    }

    private Set<UUID> directPeers(UUID userId) {
        try {
            return new HashSet<>(conversationMemberRepo.findDirectPeerIds(userId));
        } catch (Exception e) {
            log.debug("[SUGGEST] DM-peer source unavailable: {}", e.getMessage());
            return Set.of();
        }
    }

    private Map<UUID, Long> groupCoMembers(UUID userId) {
        Map<UUID, Long> out = new HashMap<>();
        try {
            for (Object[] row : conversationMemberRepo.findGroupCoMemberCounts(
                    userId, PageRequest.of(0, GROUP_CO_MEMBER_CAP))) {
                out.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        } catch (Exception e) {
            log.debug("[SUGGEST] group source unavailable: {}", e.getMessage());
        }
        return out;
    }

    private Map<UUID, Long> engagedAuthors(UUID userId) {
        Map<UUID, Long> out = new HashMap<>();
        try {
            for (UserAuthorAffinityEntity row : affinityRepo.findAllForUser(userId, AFFINITY_SCAN_CAP)) {
                if (row.getAuthorId() != null && row.getInteractions() != null && row.getInteractions() > 0) {
                    out.put(row.getAuthorId(), row.getInteractions());
                }
            }
        } catch (Exception e) {
            log.debug("[SUGGEST] affinity source unavailable: {}", e.getMessage());
        }
        return out;
    }

    private Set<UUID> institutionPeers(UUID userId, UserProfile myProfile) {
        if (myProfile == null || normalize(myProfile.getInstitutionName()) == null) return Set.of();
        try {
            return new HashSet<>(profileRepo.findUserIdsByInstitution(
                    myProfile.getInstitutionName().trim(), userId,
                    PageRequest.of(0, INSTITUTION_PEER_CAP)));
        } catch (Exception e) {
            log.debug("[SUGGEST] institution source unavailable: {}", e.getMessage());
            return Set.of();
        }
    }

    /** Keep strong-signal candidates unconditionally; fill the rest by mutual count. */
    private static Set<UUID> capCandidates(Set<UUID> candidates,
                                           Map<UUID, Integer> mutualCounts,
                                           Set<UUID> contactMatches,
                                           Set<UUID> dmPeers,
                                           Set<UUID> groupPeers) {
        Set<UUID> kept = new HashSet<>(CANDIDATE_CAP * 2);
        for (UUID c : candidates) {
            if (contactMatches.contains(c) || dmPeers.contains(c) || groupPeers.contains(c)) kept.add(c);
        }
        candidates.stream()
                .filter(c -> !kept.contains(c))
                .sorted((a, b) -> Integer.compare(
                        mutualCounts.getOrDefault(b, 0), mutualCounts.getOrDefault(a, 0)))
                .limit(Math.max(0, CANDIDATE_CAP - kept.size()))
                .forEach(kept::add);
        return kept;
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    private record ScoredCandidate(UUID candidateId, double score, String reason, String primarySource) {}

    private ScoredCandidate score(UUID candidateId, User candidate, UserProfile profile,
                                  int mutual, boolean contact, boolean bidir, boolean dm,
                                  long groups, long affinityCount,
                                  String myInstitution, String myLocation, Object myLanguage,
                                  Set<Integer> myTopics) {
        // (label shown in `reason`, source key, contribution)
        Map<String, double[]> parts = new LinkedHashMap<>();   // insertion-ordered for stable reasons
        List<String> labels = new ArrayList<>();

        double sMutual = W_MUTUAL * Math.min(mutual, MUTUAL_CAP);
        if (sMutual > 0) { parts.put("GRAPH", new double[]{sMutual}); labels.add(mutual + " mutual follow" + (mutual == 1 ? "" : "s")); }

        double sContact = contact ? W_CONTACT + (bidir ? W_CONTACT_BIDIR : 0) : 0;
        if (sContact > 0) { parts.put("CONTACTS", new double[]{sContact}); labels.add(bidir ? "in each other's contacts" : "in your contacts"); }

        double sDm = dm ? W_DM : 0;
        if (sDm > 0) { parts.put("MESSAGING", new double[]{sDm}); labels.add("you message each other"); }

        double sGroups = W_GROUP * Math.min(groups, GROUP_CAP);
        if (sGroups > 0) { parts.put("GROUPS", new double[]{sGroups}); labels.add(groups + " shared group" + (groups == 1 ? "" : "s")); }

        double sAffinity = affinityCount > 0 ? W_AFFINITY * Math.log1p(affinityCount) : 0;
        if (sAffinity > 0) { parts.put("INTERACTIONS", new double[]{sAffinity}); labels.add("you engage with their content"); }

        double sProfile = 0;
        String candInstitution = profile == null ? null : normalize(profile.getInstitutionName());
        String candLocation    = profile == null ? null : normalize(profile.getLocation());
        if (myInstitution != null && myInstitution.equals(candInstitution)) {
            sProfile += W_INSTITUTION; labels.add("same institution");
        }
        if (myLocation != null && myLocation.equals(candLocation)) {
            sProfile += W_LOCATION; labels.add("same location");
        }
        if (!myTopics.isEmpty() && profile != null) {
            Set<Integer> candTopics = topicIds(profile);
            candTopics.retainAll(myTopics);
            if (!candTopics.isEmpty()) {
                sProfile += W_SPECIALIZATION * Math.min(candTopics.size(), SPECIALIZATION_CAP);
                labels.add("shared specializations");
            }
        }
        if (myLanguage != null && profile != null && myLanguage.equals(profile.getContentLanguage())) {
            sProfile += W_LANGUAGE;
        }
        if (sProfile > 0) parts.put("AFFILIATION", new double[]{sProfile});

        // Profile quality — small, source-less nudges.
        double quality = 0;
        if (candidate.getRole() != null) {
            switch (candidate.getRole()) {
                case RESEARCHER, SCHOLAR, ADMIN -> quality += W_BADGE;
                default -> { }
            }
        }
        if (candidate.getProfileImage() != null && profile != null
                && profile.getProfileBio() != null && !profile.getProfileBio().isBlank()) {
            quality += W_COMPLETE;
        }

        double total = quality;
        String primarySource = "GRAPH";
        double best = -1;
        for (Map.Entry<String, double[]> e : parts.entrySet()) {
            total += e.getValue()[0];
            if (e.getValue()[0] > best) { best = e.getValue()[0]; primarySource = e.getKey(); }
        }

        String reason = labels.isEmpty()
                ? "Suggested for you"
                : String.join(" · ", labels.subList(0, Math.min(3, labels.size())));
        return new ScoredCandidate(candidateId, total, reason, primarySource);
    }

    /**
     * Diversity: the stored list stays score-ordered, but every source with
     * at least one surviving candidate gets representation inside the head
     * ({@value #DIVERSITY_HEAD} rows) — swapping its best candidate in for
     * the weakest head entry when necessary.
     */
    private static List<ScoredCandidate> diversify(List<ScoredCandidate> sortedDesc) {
        List<ScoredCandidate> top = new ArrayList<>(
                sortedDesc.subList(0, Math.min(MAX_SUGGESTIONS_TO_STORE, sortedDesc.size())));
        if (sortedDesc.size() <= DIVERSITY_HEAD) return top;

        Set<String> headSources = new HashSet<>();
        int head = Math.min(DIVERSITY_HEAD, top.size());
        for (int i = 0; i < head; i++) headSources.add(top.get(i).primarySource);

        for (ScoredCandidate sc : sortedDesc) {
            if (headSources.contains(sc.primarySource)) continue;
            // Best candidate of an unrepresented source — swap into the head.
            top.remove(sc);                       // may or may not be in the tail
            top.add(head - 1, sc);
            if (top.size() > MAX_SUGGESTIONS_TO_STORE) top.remove(top.size() - 1);
            headSources.add(sc.primarySource);
        }
        return top;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Stored score is ×10 fixed-point (the table's clustering column is an int). */
    private static int    scoreToStored(double score)   { return (int) Math.round(score * 10.0); }
    private static double storedToScore(Integer stored) { return stored == null ? 0 : stored / 10.0; }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        return t.isEmpty() ? null : t;
    }

    private static Set<Integer> topicIds(UserProfile profile) {
        if (profile == null || profile.getSpecializations() == null) return Set.of();
        Set<Integer> ids = new HashSet<>();
        for (UserTopicSpecialization spec : profile.getSpecializations()) {
            if (spec.getTopic() != null && spec.getTopic().getId() != null) {
                ids.add(spec.getTopic().getId());
            }
        }
        return ids;
    }
}
