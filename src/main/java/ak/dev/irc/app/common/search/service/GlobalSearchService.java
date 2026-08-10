package ak.dev.irc.app.common.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.GlobalSearchHit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.EntityAsMap;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Multi-index global search across posts (+reels), questions, answers,
 * research, users, public channels and the sound library.
 *
 * <p>One ES query hits N indices in parallel — same shard-level latency as
 * searching a single index but lets each entity keep its own field schema
 * and per-type boost weights.</p>
 *
 * <p>Reels are filtered out of the posts index by {@code postType=REEL} when
 * the caller asks for {@code type=REEL}.</p>
 *
 * <p>The result envelope carries a {@code degraded} flag so the frontend can
 * distinguish a genuine empty result from an ES failure ({@link Result#degraded}).
 * On failure we still return an empty result rather than 5xx-ing, so the rest
 * of the page renders.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSearchService {

    public enum EntityType { POST, REEL, QUESTION, RESEARCH, ANSWER, USER, CHANNEL, SOUND }

    private static final String IDX_POSTS    = "irc-posts";
    private static final String IDX_QNA      = "irc-qna";
    private static final String IDX_RESEARCH = "irc-research";
    private static final String IDX_ANSWERS  = "irc-answers";
    private static final String IDX_USERS    = "irc-users";
    private static final String IDX_CHANNELS = "irc-channels";
    private static final String IDX_SOUNDS   = "irc-sounds";

    /**
     * Indices whose documents represent long-lived entities (people,
     * channels, sounds) or evergreen knowledge (accepted answers) rather
     * than a feed of fresh content. They get a constant weight-1 baseline
     * in the function_score instead of relying on recency decay — without
     * it, an account created a year ago with no followers would multiply
     * BM25 by ~0 and vanish from results.
     */
    private static final List<String> ENTITY_INDICES =
            List.of(IDX_USERS, IDX_CHANNELS, IDX_SOUNDS, IDX_ANSWERS);

    private final ElasticsearchOperations esOps;
    private final SearchQueryLogService   queryLog;

    /**
     * Only the fields the hit-mapper actually reads leave the ES node —
     * bodies, keyword arrays, counters and everything else stay behind
     * instead of riding every response. {@code SOURCE_BARE} covers the two
     * fields needed to type/deep-link a hit; {@code SOURCE_EXPANDED} adds
     * the preview + attribution fields {@link #inlineBrief} inlines.
     */
    private static final String[] SOURCE_BARE = {"postType", "questionId"};
    private static final String[] SOURCE_EXPANDED = {
            "postType", "questionId", "createdAt",
            "title", "textContent", "body",
            "displayName", "fname", "lname", "username", "handle",
            "artistName", "authorName", "authorUsername",
            "researcherName", "researcherUsername"
    };

    /**
     * Search result envelope. {@code nextCursor} is non-null when cursor
     * mode was used and more pages may exist; {@code degraded=true} when
     * the ES call failed and {@code items} is therefore empty.
     */
    public record Result(List<GlobalSearchHit> items,
                         String nextCursor,
                         boolean degraded) {}

    /**
     * Offset-paged search (legacy mode). Prefer {@link #searchCursor} for
     * dropdowns / typeahead / live data; offset paging drifts as new content
     * lands during a scroll.
     *
     * @param query   user-supplied keywords
     * @param types   subset of entities to search; {@code null} or empty = all
     * @param page    0-indexed
     * @param size    per-page result count (across all indices)
     * @param expand  when {@code true}, inline preview fields from ES on each hit
     */
    public Result search(String query, Set<EntityType> types,
                         int page, int size, boolean expand) {
        if (query == null || query.isBlank()) {
            return new Result(List.of(), null, false);
        }
        List<String> indices = pickIndices(types);
        if (indices.isEmpty()) return new Result(List.of(), null, false);

        NativeQuery native_ = NativeQuery.builder()
                .withQuery(buildQuery(query, types))
                .withPageable(PageRequest.of(page, size))
                // Totals are never surfaced — skipping the count phase saves
                // work on every shard of every index in the fan-out.
                .withTrackTotalHits(false)
                .withSourceFilter(FetchSourceFilter.of(
                        null, expand ? SOURCE_EXPANDED : SOURCE_BARE, null))
                .build();

        SearchHits<EntityAsMap> hits;
        try {
            // Single retry on stale-pooled-connection so a transient socket
            // recycle doesn't surface as a degraded result to the user.
            hits = EsRetry.call(
                    () -> esOps.search(native_, EntityAsMap.class,
                            IndexCoordinates.of(indices.toArray(String[]::new))),
                    "[SEARCH] global");
        } catch (Exception e) {
            // ES is reachable but one of the target indexes doesn't exist
            // yet (fresh cluster with no documents of that type). That's a
            // legitimately empty result — don't flag degraded.
            if (EsRetry.isIndexNotFound(e)) {
                log.debug("[SEARCH] global: empty (missing index, ES is reachable): {}",
                        e.getMessage());
                return new Result(List.of(), null, false);
            }
            log.warn("[SEARCH] global search failed: {}", e.getMessage());
            queryLog.record(query, types, 0, true);
            return new Result(List.of(), null, true);
        }
        List<GlobalSearchHit> items = toItems(hits, expand);
        if (page == 0) queryLog.record(query, types, items.size(), false);
        return new Result(items, null, false);
    }

    /**
     * Cursor-paged search using ES {@code search_after} on (score, _doc).
     * Stable across inserts: a new document landing mid-scroll won't shift
     * any subsequent page, and no row is ever duplicated or skipped.
     *
     * @param cursor opaque token returned by the previous page; null/blank for the head
     */
    public Result searchCursor(String query, Set<EntityType> types,
                               int size, String cursor, boolean expand) {
        if (query == null || query.isBlank()) {
            return new Result(List.of(), null, false);
        }
        List<String> indices = pickIndices(types);
        if (indices.isEmpty()) return new Result(List.of(), null, false);

        List<SortOptions> sort = List.of(
                SortOptions.of(s -> s.score(o -> o.order(SortOrder.Desc))),
                SortOptions.of(s -> s.doc(o -> o.order(SortOrder.Asc))));

        List<FieldValue> after = Cursor.decode(cursor);

        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(buildQuery(query, types))
                .withPageable(PageRequest.of(0, size))
                .withTrackTotalHits(false)
                .withSourceFilter(FetchSourceFilter.of(
                        null, expand ? SOURCE_EXPANDED : SOURCE_BARE, null))
                .withSort(sort);
        // Spring Data ES forwards searchAfter into the underlying query body.
        if (after != null) {
            builder.withSearchAfter(after.stream().map(this::toJavaSortValue).toList());
        }

        SearchHits<EntityAsMap> hits;
        try {
            hits = EsRetry.call(
                    () -> esOps.search(builder.build(), EntityAsMap.class,
                            IndexCoordinates.of(indices.toArray(String[]::new))),
                    "[SEARCH] global cursor");
        } catch (Exception e) {
            if (EsRetry.isIndexNotFound(e)) {
                log.debug("[SEARCH] global cursor: empty (missing index): {}", e.getMessage());
                return new Result(List.of(), null, false);
            }
            log.warn("[SEARCH] cursor search failed: {}", e.getMessage());
            return new Result(List.of(), null, true);
        }

        List<GlobalSearchHit> items = toItems(hits, expand);
        // Only the head request counts — follow-up cursor pages are the same search.
        if (cursor == null || cursor.isBlank()) {
            queryLog.record(query, types, items.size(), false);
        }
        String nextCursor = Cursor.encode(lastSortValues(hits));
        return new Result(items, nextCursor, false);
    }

    // ── Query building ───────────────────────────────────────────────────────

    private static List<String> pickIndices(Set<EntityType> types) {
        Set<EntityType> active = (types == null || types.isEmpty())
                ? EnumSet.allOf(EntityType.class) : types;
        List<String> indices = new ArrayList<>(7);
        if (active.contains(EntityType.POST) || active.contains(EntityType.REEL)) indices.add(IDX_POSTS);
        if (active.contains(EntityType.QUESTION)) indices.add(IDX_QNA);
        if (active.contains(EntityType.RESEARCH)) indices.add(IDX_RESEARCH);
        if (active.contains(EntityType.ANSWER))   indices.add(IDX_ANSWERS);
        if (active.contains(EntityType.USER))     indices.add(IDX_USERS);
        if (active.contains(EntityType.CHANNEL))  indices.add(IDX_CHANNELS);
        if (active.contains(EntityType.SOUND))    indices.add(IDX_SOUNDS);
        return indices;
    }

    /**
     * Statuses that mean "should not surface in search results" across
     * every index. {@code mustNot term} on a field that doesn't exist on
     * a given index is a silent no-op, so the same filter applies safely
     * to posts, questions, and research alike.
     */
    private static final List<String> DEAD_STATUSES = List.of(
            "DELETED", "DRAFT", "ARCHIVED",
            "RETRACTED", "REMOVED_BY_MODERATOR",
            "REMOVED",                          // PostStatus moderation removal
            "PENDING_REVIEW", "REJECTED"        // SoundStatus pre-approval states
    );

    /**
     * Visibility levels that mean "not public" — never surfaced by search,
     * not even to the owner (search is a public discovery surface; private
     * content is reached through its own feeds). Same silent-no-op trick as
     * {@link #DEAD_STATUSES}: indices without a {@code visibility} field
     * (Q&amp;A, users, channels, sounds) are unaffected.
     */
    private static final List<String> NON_PUBLIC_VISIBILITIES = List.of(
            "FOLLOWERS_ONLY", "ONLY_ME",        // PostVisibility
            "PRIVATE"                           // ResearchVisibility
    );

    // Prebuilt FieldValue lists — buildQuery runs per request; these never change.
    private static final List<FieldValue> DEAD_STATUS_VALUES =
            DEAD_STATUSES.stream().map(FieldValue::of).toList();
    private static final List<FieldValue> NON_PUBLIC_VALUES =
            NON_PUBLIC_VISIBILITIES.stream().map(FieldValue::of).toList();
    private static final List<FieldValue> ENTITY_INDEX_VALUES =
            ENTITY_INDICES.stream().map(FieldValue::of).toList();

    /**
     * Multi-power query: combines text relevance, typo tolerance, phrase
     * boost, prefix typeahead and lifecycle filtering inside a
     * {@code bool}, then wraps the whole bool in a {@code function_score}
     * that mixes BM25 with recency decay and engagement signals.
     *
     * <p>Layers:</p>
     * <ol>
     *   <li><b>Primary recall</b> — {@code multi_match} (BestFields)
     *       with {@code fuzziness=AUTO} (typo tolerance) and
     *       {@code minimum_should_match=75%} so a 4-word query needs at
     *       least 3 tokens to match instead of any-one.</li>
     *   <li><b>Phrase boost</b> — parallel {@code match_phrase} in
     *       {@code should}; exact phrases dominate scattered tokens.</li>
     *   <li><b>Typeahead</b> — {@code match_phrase_prefix} catches the
     *       last token while it's still being typed
     *       ("ramad" → "ramadan").</li>
     *   <li><b>Lifecycle filter</b> — {@code mustNot} on dead statuses
     *       defends against indexer regressions that might leak drafts /
     *       deleted content into the index.</li>
     *   <li><b>Recency decay</b> — Gaussian decay on {@code createdAt}
     *       (half-strength at 30d, near-zero at 180d). Equal-relevance
     *       hits prefer the newer document.</li>
     *   <li><b>Engagement boost</b> — log1p of {@code reactionCount}
     *       added to the score so widely-loved content outranks
     *       equally-relevant but ignored content.</li>
     * </ol>
     */
    private static Query buildQuery(String query, Set<EntityType> types) {
        Set<EntityType> active = (types == null || types.isEmpty())
                ? EnumSet.allOf(EntityType.class) : types;

        Query coreBool = Query.of(q -> q.bool(b -> {
            // 1) Primary recall — fuzzy, multi-field, weighted.
            b.must(m -> m.multiMatch(mm -> mm
                    .query(query)
                    .fields(
                            // Strong-signal fields (titles + body text)
                            "title^4", "textContent^3",
                            "abstractText^2", "keywords^2", "body^2",
                            // Identity handles — a handle search should nail
                            // the account/channel it names. The *Tokens
                            // variants carry the handle pre-split on ._- so
                            // individual segments match too.
                            "username^4", "handle^4", "displayName^3",
                            "usernameTokens^3", "handleTokens^3",
                            // Tags / hashtags — useful but partial
                            "tags^2", "hashtags^2",
                            // People-index name parts + affiliation
                            "fname^2", "lname^2", "bio",
                            "academicTitle", "institutionName",
                            // Answer context + sound artist
                            "questionTitle^2", "artistName^2",
                            "description",
                            // People + place on content docs
                            "authorName", "authorUsername",
                            "researcherName", "researcherUsername",
                            "locationName"
                    )
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO")
                    .tieBreaker(0.3)
                    .minimumShouldMatch("75%")));

            // 2) Exact-phrase relevance — rewards "ramadan zakat" beating
            //    one doc with "ramadan" and another with "zakat" scattered.
            b.should(m -> m.multiMatch(mm -> mm
                    .query(query)
                    .fields("title^4", "textContent^3", "body^2",
                            "abstractText^2", "displayName^2", "description")
                    .type(TextQueryType.Phrase)
                    .boost(2.0f)));

            // 3) Typeahead — partial last word catches in-flight queries
            //    without a dedicated ngram field on every doc.
            b.should(m -> m.multiMatch(mm -> mm
                    .query(query)
                    .fields("title^3", "username^3", "handle^3",
                            "displayName^2", "textContent^2", "body",
                            "abstractText", "artistName",
                            "authorName", "authorUsername",
                            "researcherName", "researcherUsername")
                    .type(TextQueryType.PhrasePrefix)
                    .boost(1.5f)));

            // 4) Reels-only requests narrow the posts index by postType.
            if (active.contains(EntityType.REEL) && !active.contains(EntityType.POST)) {
                b.filter(f -> f.term(t -> t.field("postType").value("REEL")));
            }

            // 5) Lifecycle filter — dead statuses never surface. A single
            //    terms clause (set lookup) instead of one mustNot per status.
            b.mustNot(mn -> mn.terms(t -> t.field("status")
                    .terms(v -> v.value(DEAD_STATUS_VALUES))));

            // 6) Privacy filter — non-public content never surfaces.
            //    (mustNot on a field an index doesn't have is a no-op, so
            //    this only bites posts + research, the two visibility-bearing
            //    indices.)
            b.mustNot(mn -> mn.terms(t -> t.field("visibility")
                    .terms(v -> v.value(NON_PUBLIC_VALUES))));
            return b;
        }));

        // 6) function_score wrapper — adds recency + engagement on top of
        //    BM25 without changing the recall set.
        return Query.of(q -> q.functionScore(fs -> fs
                .query(coreBool)
                // Recency: half-weight at 30 days, near-zero by 180 days.
                // Uses the "untyped" decay variant — origin/scale are raw
                // JsonData strings so the same call covers Date / Number /
                // any other indexed type.
                .functions(fn -> fn
                        .gauss(g -> g.untyped(u -> u
                                .field("createdAt")
                                .placement(p -> p
                                        .origin(JsonData.of("now"))
                                        .scale(JsonData.of("30d"))
                                        .offset(JsonData.of("1d"))
                                        .decay(0.5)))))
                // Engagement — per-index, additive. Each function references
                // a field that only exists on one or two indices; the
                // `missing(0.0)` clause turns absence into zero contribution
                // so a doc only earns the boost for signals it actually
                // carries. Factors are calibrated to the typical max of each
                // metric so a viral post and a much-cited paper end up
                // contributing comparable score magnitudes after log1p.
                //
                //   posts:    reactionCount   — social signal, typical max ~1000
                //   research: citationCount   — academic gold, typical max ~50
                //                 + smaller reactionCount tap-in (research is
                //                   also reactable, but citations dominate)
                //   qna:      answerCount     — "more answers = better question";
                //                 small absolute values (0–20), so larger factor
                // Scores combine via score_mode=SUM, then multiply BM25
                // (boost_mode=MULTIPLY).
                .functions(fn -> fn
                        .fieldValueFactor(fv -> fv
                                .field("reactionCount")
                                .factor(1.0)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                .functions(fn -> fn
                        .fieldValueFactor(fv -> fv
                                .field("answerCount")
                                .factor(2.0)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                .functions(fn -> fn
                        .fieldValueFactor(fv -> fv
                                .field("citationCount")
                                .factor(1.5)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                // Q&A secondary — viewCount, scoped via `_index` filter so
                // it only fires on irc-qna docs. Smaller factor (0.5) than
                // the primary answerCount boost so it acts as a tiebreaker
                // between two answer-equivalent questions, never overriding
                // the "more answers = better question" signal. Posts and
                // research also carry viewCount but are excluded — viewCount
                // is noisier than reactions/citations as a quality signal,
                // and adding it everywhere would inflate older posts that
                // accumulated views without earning real engagement.
                .functions(fn -> fn
                        .filter(f -> f.term(t -> t.field("_index").value("irc-qna")))
                        .fieldValueFactor(fv -> fv
                                .field("viewCount")
                                .factor(0.5)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                // Posts secondary — commentCount, scoped via `_index` filter
                // so it only fires on irc-posts docs. Acts as a tiebreaker
                // between two like-equivalent posts: a heavily-discussed
                // post sits above a same-reaction-count post with no
                // conversation. Half the factor of the primary
                // reactionCount boost so the "discussion" signal never
                // overrides the "approval" signal — a post with 10 likes
                // and 50 comments still ranks below one with 100 likes and
                // no comments at equal relevance.
                .functions(fn -> fn
                        .filter(f -> f.term(t -> t.field("_index").value("irc-posts")))
                        .fieldValueFactor(fv -> fv
                                .field("commentCount")
                                .factor(0.5)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                // Research secondary — downloadCount, scoped via `_index`
                // filter so it only fires on irc-research docs. Tiebreaker
                // between two similarly-cited papers: a paper that's been
                // downloaded for study (PDF / dataset) ranks above a
                // paper of equal citation count with no downloads. Half
                // the factor of the primary citationCount boost so peer
                // recognition (citations) always dominates raw access
                // (downloads).
                .functions(fn -> fn
                        .filter(f -> f.term(t -> t.field("_index").value("irc-research")))
                        .fieldValueFactor(fv -> fv
                                .field("downloadCount")
                                .factor(0.5)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                // Entity-index baseline — people / channels / sounds /
                // answers are long-lived, so they get a constant 1.0 instead
                // of living or dying by the recency gauss. Without this, a
                // year-old account with no followers multiplies BM25 by ~0.
                .functions(fn -> fn
                        .filter(f -> f.terms(t -> t.field("_index")
                                .terms(v -> v.value(ENTITY_INDEX_VALUES))))
                        .weight(1.0))
                // Popularity signals for the entity indices — each field
                // exists on exactly one index; missing(0.0) keeps the rest
                // untouched. log1p keeps a 1M-follower account from
                // drowning an exact-handle match on a small one.
                //
                //   users:    followerCount    — reach
                //   channels: subscriberCount  — reach
                //   sounds:   useCount         — adoption
                .functions(fn -> fn
                        .fieldValueFactor(fv -> fv
                                .field("followerCount")
                                .factor(1.0)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                .functions(fn -> fn
                        .fieldValueFactor(fv -> fv
                                .field("subscriberCount")
                                .factor(1.0)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                .functions(fn -> fn
                        .fieldValueFactor(fv -> fv
                                .field("useCount")
                                .factor(1.0)
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0)))
                // Accepted answers — the author marked this one as THE
                // answer; add a flat +2.0 so it outranks sibling answers of
                // equal text relevance.
                .functions(fn -> fn
                        .filter(f -> f.term(t -> t.field("accepted").value(true)))
                        .weight(2.0))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Multiply)));
    }

    // ── Hit → DTO ────────────────────────────────────────────────────────────

    private List<GlobalSearchHit> toItems(SearchHits<EntityAsMap> hits, boolean expand) {
        List<GlobalSearchHit> out = new ArrayList<>(hits.getSearchHits().size());
        for (SearchHit<EntityAsMap> h : hits.getSearchHits()) {
            GlobalSearchHit hit = toHit(h, expand);
            if (hit != null) out.add(hit);
        }
        return out;
    }

    private GlobalSearchHit toHit(SearchHit<EntityAsMap> h, boolean expand) {
        UUID id;
        try {
            id = UUID.fromString(h.getId());
        } catch (Exception e) {
            return null;
        }
        String type = switch (h.getIndex()) {
            case IDX_POSTS    -> deriveSocialType(h);
            case IDX_QNA      -> EntityType.QUESTION.name();
            case IDX_RESEARCH -> EntityType.RESEARCH.name();
            case IDX_ANSWERS  -> EntityType.ANSWER.name();
            case IDX_USERS    -> EntityType.USER.name();
            case IDX_CHANNELS -> EntityType.CHANNEL.name();
            case IDX_SOUNDS   -> EntityType.SOUND.name();
            default           -> null;
        };
        if (type == null) return null;

        GlobalSearchHit.GlobalSearchHitBuilder b = GlobalSearchHit.builder()
                .contentType(type).contentId(id)
                .type(type).id(id)
                .score(h.getScore());
        // Answers always carry their question id (regardless of expand) —
        // it's required to deep-link the hit, not a preview nicety.
        if (EntityType.ANSWER.name().equals(type)) {
            b.parentId(sourceUuid(h, "questionId"));
        }
        if (expand) inlineBrief(b, h, type);
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private static UUID sourceUuid(SearchHit<EntityAsMap> h, String field) {
        Object src = h.getContent();
        if (!(src instanceof Map)) return null;
        Object v = ((Map<String, Object>) src).get(field);
        if (v == null) return null;
        try { return UUID.fromString(v.toString()); }
        catch (Exception e) { return null; }
    }

    /**
     * Pulls preview fields out of the ES source document so the frontend can
     * render search dropdowns without a follow-up hydration call per hit.
     * Only viewer-independent fields are inlined; saved/reacted/etc. stay out.
     */
    @SuppressWarnings("unchecked")
    private void inlineBrief(GlobalSearchHit.GlobalSearchHitBuilder b,
                             SearchHit<EntityAsMap> h, String type) {
        Object src = h.getContent();
        if (!(src instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) src;

        // Each type has its own natural preview + attribution fields.
        switch (type) {
            case "USER" -> {
                b.titlePreview(trimPreview(firstNonBlank(
                        str(m.get("displayName")),
                        joinNames(str(m.get("fname")), str(m.get("lname"))))));
                b.authorUsername(str(m.get("username")));
                b.authorName(firstNonBlank(
                        str(m.get("displayName")),
                        joinNames(str(m.get("fname")), str(m.get("lname")))));
            }
            case "CHANNEL" -> {
                b.titlePreview(trimPreview(str(m.get("title"))));
                b.authorUsername(str(m.get("handle")));   // channel @handle
            }
            case "SOUND" -> {
                b.titlePreview(trimPreview(str(m.get("title"))));
                b.authorName(str(m.get("artistName")));
            }
            case "ANSWER" -> {
                b.titlePreview(trimPreview(str(m.get("body"))));
                b.authorUsername(str(m.get("authorUsername")));
                b.authorName(str(m.get("authorName")));
            }
            default -> {
                // POST / REEL / QUESTION / RESEARCH — index-specific preview
                // field name; fall through to the next candidate.
                b.titlePreview(trimPreview(firstNonBlank(
                        str(m.get("title")),         // qna / research
                        str(m.get("textContent"))))); // post
                b.authorUsername(firstNonBlank(
                        str(m.get("authorUsername")),
                        str(m.get("researcherUsername"))));
                b.authorName(firstNonBlank(
                        str(m.get("authorName")),
                        str(m.get("researcherName"))));
            }
        }
        Object createdAt = m.get("createdAt");
        if (createdAt instanceof String s && !s.isBlank()) {
            try { b.createdAt(Instant.parse(s)); }
            catch (Exception ignored) { /* leave null */ }
        }
    }

    private static String trimPreview(String s) {
        if (s == null) return null;
        return s.length() > 280 ? s.substring(0, 280) : s;
    }

    private static String joinNames(String fname, String lname) {
        if (fname == null && lname == null) return null;
        if (fname == null) return lname;
        if (lname == null) return fname;
        return fname + " " + lname;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c;
        return null;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    /** Posts and reels share an index — distinguish by the postType source field. */
    @SuppressWarnings("unchecked")
    private String deriveSocialType(SearchHit<EntityAsMap> h) {
        Object src = h.getContent();
        if (src instanceof Map<?, ?> m) {
            Object pt = ((Map<String, Object>) m).get("postType");
            if (pt != null && "REEL".equalsIgnoreCase(pt.toString())) return EntityType.REEL.name();
        }
        return EntityType.POST.name();
    }

    // ── Cursor encoding for search_after ─────────────────────────────────────

    private List<FieldValue> lastSortValues(SearchHits<EntityAsMap> hits) {
        if (hits.isEmpty()) return null;
        List<Object> sortValues = hits.getSearchHits()
                .get(hits.getSearchHits().size() - 1).getSortValues();
        if (sortValues == null || sortValues.isEmpty()) return null;
        List<FieldValue> out = new ArrayList<>(sortValues.size());
        for (Object v : sortValues) out.add(toFieldValue(v));
        return out;
    }

    private FieldValue toFieldValue(Object v) {
        if (v == null) return FieldValue.NULL;
        if (v instanceof Number n)  return FieldValue.of(n.doubleValue());
        if (v instanceof Boolean b) return FieldValue.of(b);
        return FieldValue.of(v.toString());
    }

    private Object toJavaSortValue(FieldValue fv) {
        // search_after takes plain Java values, not FieldValue wrappers.
        return switch (fv._kind()) {
            case Double -> fv.doubleValue();
            case Long   -> fv.longValue();
            case Boolean -> fv.booleanValue();
            case String -> fv.stringValue();
            case Null   -> null;
            default     -> fv.toString();
        };
    }

    private final class Cursor {
        /** Shared, thread-safe mapper — ObjectMapper construction is expensive
         *  and was previously paid on every cursor encode/decode. */
        private static final ObjectMapper CURSOR_MAPPER = new ObjectMapper();

        static String encode(List<FieldValue> sortValues) {
            if (sortValues == null || sortValues.isEmpty()) return null;
            try {
                List<Object> raw = new ArrayList<>(sortValues.size());
                for (FieldValue fv : sortValues) {
                    raw.add(switch (fv._kind()) {
                        case Double  -> fv.doubleValue();
                        case Long    -> fv.longValue();
                        case Boolean -> fv.booleanValue();
                        case String  -> fv.stringValue();
                        case Null    -> null;
                        default      -> fv.toString();
                    });
                }
                String json = CURSOR_MAPPER.writeValueAsString(raw);
                return Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            } catch (JsonProcessingException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static List<FieldValue> decode(String token) {
            if (token == null || token.isBlank()) return null;
            try {
                String json = new String(Base64.getUrlDecoder().decode(token),
                        StandardCharsets.UTF_8);
                List<Object> raw = CURSOR_MAPPER.readValue(json, List.class);
                List<FieldValue> out = new ArrayList<>(raw.size());
                for (Object v : raw) {
                    if (v == null) out.add(FieldValue.NULL);
                    else if (v instanceof Number n) out.add(FieldValue.of(n.doubleValue()));
                    else if (v instanceof Boolean b) out.add(FieldValue.of(b));
                    else out.add(FieldValue.of(v.toString()));
                }
                return out;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
