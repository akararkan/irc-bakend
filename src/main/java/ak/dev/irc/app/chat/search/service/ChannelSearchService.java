package ak.dev.irc.app.chat.search.service;

import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.search.document.ChannelSearchDocument;
import ak.dev.irc.app.chat.search.repository.ChannelSearchRepository;
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.ReindexSummary;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch indexer + query service for the {@code irc-channels}
 * public-channel discovery index.
 *
 * <p>Write side: {@link #indexAsync} is called from every channel mutation
 * (create / profile update / verify / subscribe / unsubscribe). It is the
 * privacy gate — a channel that is not {@code publicChannel}, or is deleted,
 * is <em>removed</em> from the index rather than written.</p>
 *
 * <p>Read side: {@link #searchIds} serves ranked channel discovery for
 * {@code ChannelService.discover} — BM25 over title/handle/description with
 * typo tolerance and prefix typeahead, multiplied by a log1p subscriber-count
 * boost so big channels win ties without drowning exact matches.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelSearchService {

    private final ChannelSearchRepository searchRepo;
    private final ConversationRepository  conversationRepo;
    private final ElasticsearchOperations esOps;

    // ── Indexing ────────────────────────────────────────────────────────────

    /**
     * Sync the index row for one channel. Public + live → upsert;
     * private / deleted / not-a-channel → remove. Safe to call from any
     * mutation path without pre-checks.
     */
    @Async
    public void indexAsync(Conversation c) {
        if (c == null || c.getId() == null) return;
        try {
            // The held check lives here rather than at each call site because
            // subscribe/unsubscribe/verify also re-index, and a channel whose title
            // has not cleared automated moderation must not become discoverable
            // through any of them.
            if (!c.isChannel() || !c.isPublicChannel() || c.getDeletedAt() != null
                    || ChatModeration.held(c.getModerationStatus())) {
                EsRetry.run(() -> searchRepo.deleteById(c.getId().toString()),
                        "[SEARCH] de-index channel " + c.getId());
                return;
            }
            EsRetry.run(() -> searchRepo.save(buildDoc(c)),
                    "[SEARCH] index channel " + c.getId());
        } catch (Exception e) {
            log.warn("[SEARCH] index channel {} failed: {}", c.getId(), e.getMessage());
        }
    }

    /** Remove a channel from the index (hard-delete path). */
    @Async
    public void deleteAsync(UUID channelId) {
        if (channelId == null) return;
        try {
            EsRetry.run(() -> searchRepo.deleteById(channelId.toString()),
                    "[SEARCH] delete channel " + channelId);
        } catch (Exception e) {
            log.warn("[SEARCH] delete channel {} failed: {}", channelId, e.getMessage());
        }
    }

    // ── Query ───────────────────────────────────────────────────────────────

    /**
     * Ranked public-channel search. Returns channel ids best-first; the
     * caller hydrates from Postgres (which re-checks public + live state,
     * so a stale index row can never leak a now-private channel).
     *
     * @param q        free text (must be non-blank — caller handles the
     *                 blank-query "browse all" listing separately)
     * @param category optional exact category filter
     * @param limit    max ids returned
     * @throws RuntimeException when ES is unreachable — caller falls back
     *         to the Postgres LIKE scan
     */
    public List<UUID> searchIds(String q, String category, int limit) {
        Query bool = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields("handle^4", "handleTokens^3", "title^3", "description")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO")
                    .tieBreaker(0.3)));
            // Typeahead: catch the in-flight last word ("ilm" → "ilmhub").
            b.should(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields("handle^3", "title^2")
                    .type(TextQueryType.PhrasePrefix)
                    .boost(1.5f)));
            // Belt-and-braces: write side never indexes private channels,
            // but filter anyway so a bug there can't become a privacy leak.
            b.filter(f -> f.term(t -> t.field("publicChannel").value(true)));
            if (category != null && !category.isBlank()) {
                b.filter(f -> f.term(t -> t.field("category").value(category)));
            }
            return b;
        }));

        // score = BM25 × (1 + log1p(subscribers)): popularity breaks ties,
        // relevance stays in charge.
        Query scored = Query.of(qb -> qb.functionScore(fs -> fs
                .query(bool)
                .functions(fn -> fn.weight(1.0))
                .functions(fn -> fn.fieldValueFactor(fv -> fv
                        .field("subscriberCount")
                        .factor(1.0)
                        .modifier(FieldValueFactorModifier.Log1p)
                        .missing(0.0)))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Multiply)));

        NativeQuery query = NativeQuery.builder()
                .withQuery(scored)
                .withPageable(PageRequest.of(0, limit))
                .build();

        SearchHits<ChannelSearchDocument> hits = EsRetry.call(
                () -> esOps.search(query, ChannelSearchDocument.class),
                "[SEARCH] channels");
        List<UUID> ids = new ArrayList<>(hits.getSearchHits().size());
        for (SearchHit<ChannelSearchDocument> h : hits.getSearchHits()) {
            try { ids.add(UUID.fromString(h.getId())); } catch (Exception ignored) { }
        }
        return ids;
    }

    // ── Reindex ─────────────────────────────────────────────────────────────

    /** One-shot reindex of every live public channel. Synchronous. */
    @Transactional(readOnly = true)
    public ReindexSummary reindexAll(boolean dropFirst) {
        Instant start = Instant.now();
        boolean dropped = false;
        if (dropFirst) {
            try {
                EsRetry.run(() -> esOps.indexOps(ChannelSearchDocument.class).delete(),
                        "[SEARCH] drop irc-channels");
                dropped = true;
            } catch (Exception e) {
                log.warn("[SEARCH] irc-channels drop best-effort failed (often harmless): {}", e.getMessage());
            }
            // Recreate from the annotated mapping immediately — relying on the
            // first save() would dynamic-map every Keyword field back to text.
            try {
                EsRetry.run(() -> esOps.indexOps(ChannelSearchDocument.class).createWithMapping(),
                        "[SEARCH] recreate irc-channels mapping");
            } catch (Exception e) {
                log.warn("[SEARCH] irc-channels mapping recreate failed: {}", e.getMessage());
            }
        }

        final int PAGE_SIZE = 100;
        long indexed = 0;
        int pages = 0, page = 0;
        while (true) {
            Page<Conversation> slice = conversationRepo
                    .findByTypeAndPublicChannelTrueAndDeletedAtIsNull(
                            ConversationType.CHANNEL, PageRequest.of(page, PAGE_SIZE));
            if (slice.isEmpty()) break;

            List<ChannelSearchDocument> docs = new ArrayList<>(slice.getNumberOfElements());
            for (Conversation c : slice.getContent()) {
                if (ChatModeration.held(c.getModerationStatus())) continue;  // same gate as indexAsync
                docs.add(buildDoc(c));
            }
            try {
                EsRetry.run(() -> searchRepo.saveAll(docs),
                        "[SEARCH] reindex channels page " + page);
                indexed += docs.size();
                pages++;
            } catch (Exception e) {
                log.warn("[SEARCH] channel reindex page {} failed ({} docs skipped): {}",
                        page, docs.size(), e.getMessage());
            }
            if (!slice.hasNext()) break;
            page++;
        }

        long ms = Duration.between(start, Instant.now()).toMillis();
        String note = String.format("Reindexed %d public channel(s) across %d page(s)%s.",
                indexed, pages, dropped ? " (index was dropped first)" : "");
        log.info("[SEARCH] {} ({}ms)", note, ms);
        return new ReindexSummary(dropped, indexed, pages, ms, note);
    }

    /** "ilm_hub-daily" → "ilm hub daily" so each segment is independently matchable. */
    private static String splitHandle(String handle) {
        if (handle == null || handle.isBlank()) return null;
        return handle.replaceAll("[._\\-]+", " ").trim();
    }

    private static ChannelSearchDocument buildDoc(Conversation c) {
        return ChannelSearchDocument.builder()
                .id(ChannelSearchDocument.idOf(c.getId()))
                .title(c.getTitle())
                .handle(c.getHandle())
                .handleTokens(splitHandle(c.getHandle()))
                .description(c.getDescription())
                .category(c.getCategory())
                .verified(c.isVerified())
                .publicChannel(true)
                .status("ACTIVE")
                .subscriberCount((long) c.getMemberCount())
                .createdAt(c.getCreatedAt() == null ? null
                        : c.getCreatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }
}
