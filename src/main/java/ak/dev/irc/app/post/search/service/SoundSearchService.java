package ak.dev.irc.app.post.search.service;

import ak.dev.irc.app.common.search.EsRetry;
import ak.dev.irc.app.common.search.dto.ReindexSummary;
import ak.dev.irc.app.post.cassandra.entity.SoundEntity;
import ak.dev.irc.app.post.cassandra.repository.SoundCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.SoundRepository;
import ak.dev.irc.app.post.search.document.SoundSearchDocument;
import ak.dev.irc.app.post.search.repository.SoundSearchRepository;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch indexer + query service for the {@code irc-sounds}
 * sound-library index.
 *
 * <p>Write side: called from {@code CassandraSoundService} on upload,
 * approval and per-use count bumps. {@link #refreshAsync} re-loads the sound
 * and its use counter inside the async thread, so hot paths (post create
 * with a sound) pay nothing.</p>
 *
 * <p>Read side: {@link #searchIds} powers {@code GET /api/v1/sounds/search}
 * — typo-tolerant title/artist match ranked by relevance × log1p(useCount),
 * APPROVED-only.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoundSearchService {

    private final SoundSearchRepository  searchRepo;
    private final SoundRepository        soundRepo;
    private final SoundCounterRepository soundCounterRepo;
    private final ElasticsearchOperations esOps;

    // ── Indexing ────────────────────────────────────────────────────────────

    /** Index a sound with a known use count (upload/approve paths). */
    @Async
    public void indexAsync(SoundEntity sound, long useCount) {
        if (sound == null || sound.getId() == null) return;
        try {
            EsRetry.run(() -> searchRepo.save(buildDoc(sound, useCount)),
                    "[SEARCH] index sound " + sound.getId());
        } catch (Exception e) {
            log.warn("[SEARCH] index sound {} failed: {}", sound.getId(), e.getMessage());
        }
    }

    /**
     * Reload sound + use counter and re-index — called after a use-count
     * bump so popularity ranking stays fresh without touching the post path.
     */
    @Async
    public void refreshAsync(UUID soundId) {
        if (soundId == null) return;
        try {
            SoundEntity s = soundRepo.findById(soundId).orElse(null);
            if (s == null) {
                EsRetry.run(() -> searchRepo.deleteById(soundId.toString()),
                        "[SEARCH] de-index missing sound " + soundId);
                return;
            }
            long uses = soundCounterRepo.findBySoundId(soundId)
                    .map(c -> c.getUseCount() == null ? 0L : c.getUseCount()).orElse(0L);
            EsRetry.run(() -> searchRepo.save(buildDoc(s, uses)),
                    "[SEARCH] refresh sound " + soundId);
        } catch (Exception e) {
            log.warn("[SEARCH] refresh sound {} failed: {}", soundId, e.getMessage());
        }
    }

    @Async
    public void deleteAsync(UUID soundId) {
        if (soundId == null) return;
        try {
            EsRetry.run(() -> searchRepo.deleteById(soundId.toString()),
                    "[SEARCH] delete sound " + soundId);
        } catch (Exception e) {
            log.warn("[SEARCH] delete sound {} failed: {}", soundId, e.getMessage());
        }
    }

    // ── Query ───────────────────────────────────────────────────────────────

    /**
     * Ranked APPROVED-sound search, best-first. Caller hydrates the ids
     * from Cassandra ({@code sounds_by_id}) preserving this order.
     */
    public List<UUID> searchIds(String q, String category, int limit) {
        Query bool = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields("title^3", "artistName^2")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO")
                    .tieBreaker(0.3)));
            b.should(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields("title^2", "artistName")
                    .type(TextQueryType.PhrasePrefix)
                    .boost(1.5f)));
            b.filter(f -> f.term(t -> t.field("status").value("APPROVED")));
            if (category != null && !category.isBlank()) {
                b.filter(f -> f.term(t -> t.field("category").value(category)));
            }
            return b;
        }));

        Query scored = Query.of(qb -> qb.functionScore(fs -> fs
                .query(bool)
                .functions(fn -> fn.weight(1.0))
                .functions(fn -> fn.fieldValueFactor(fv -> fv
                        .field("useCount")
                        .factor(1.0)
                        .modifier(FieldValueFactorModifier.Log1p)
                        .missing(0.0)))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Multiply)));

        NativeQuery query = NativeQuery.builder()
                .withQuery(scored)
                .withPageable(PageRequest.of(0, limit))
                .build();

        SearchHits<SoundSearchDocument> hits = EsRetry.call(
                () -> esOps.search(query, SoundSearchDocument.class),
                "[SEARCH] sounds");
        List<UUID> ids = new ArrayList<>(hits.getSearchHits().size());
        for (SearchHit<SoundSearchDocument> h : hits.getSearchHits()) {
            try { ids.add(UUID.fromString(h.getId())); } catch (Exception ignored) { }
        }
        return ids;
    }

    // ── Reindex ─────────────────────────────────────────────────────────────

    /**
     * One-shot reindex of the whole sound library. The library is a bounded,
     * curated catalog (not user-content scale), so a full Cassandra table
     * walk + one counter point-read per sound is acceptable here.
     */
    public ReindexSummary reindexAll(boolean dropFirst) {
        Instant start = Instant.now();
        boolean dropped = false;
        if (dropFirst) {
            try {
                EsRetry.run(() -> esOps.indexOps(SoundSearchDocument.class).delete(),
                        "[SEARCH] drop irc-sounds");
                dropped = true;
            } catch (Exception e) {
                log.warn("[SEARCH] irc-sounds drop best-effort failed (often harmless): {}", e.getMessage());
            }
            // Recreate from the annotated mapping immediately — relying on the
            // first save() would dynamic-map every Keyword field back to text.
            try {
                EsRetry.run(() -> esOps.indexOps(SoundSearchDocument.class).createWithMapping(),
                        "[SEARCH] recreate irc-sounds mapping");
            } catch (Exception e) {
                log.warn("[SEARCH] irc-sounds mapping recreate failed: {}", e.getMessage());
            }
        }

        long indexed = 0;
        List<SoundEntity> pending = new ArrayList<>(100);
        List<SoundSearchDocument> batch = new ArrayList<>(100);
        for (SoundEntity s : soundRepo.findAll()) {
            pending.add(s);
            if (pending.size() == 100) {
                buildDocsBulk(pending, batch);
                indexed += flush(batch);
            }
        }
        buildDocsBulk(pending, batch);
        indexed += flush(batch);

        long ms = Duration.between(start, Instant.now()).toMillis();
        String note = String.format("Reindexed %d sound(s)%s.",
                indexed, dropped ? " (index was dropped first)" : "");
        log.info("[SEARCH] {} ({}ms)", note, ms);
        return new ReindexSummary(dropped, indexed, (int) ((indexed + 99) / 100), ms, note);
    }

    /**
     * Resolve use-counts for a batch of sounds with ONE counter IN-query
     * (the old loop point-read sound_counters once per sound) and drain the
     * batch into ES documents.
     */
    private void buildDocsBulk(List<SoundEntity> pending, List<SoundSearchDocument> out) {
        if (pending.isEmpty()) return;
        java.util.Map<java.util.UUID, Long> uses = new java.util.HashMap<>(pending.size());
        try {
            java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
            pending.forEach(s -> ids.add(s.getId()));
            soundCounterRepo.findAllBySoundIdIn(ids).forEach(c ->
                    uses.put(c.getSoundId(), c.getUseCount() == null ? 0L : c.getUseCount()));
        } catch (Exception e) {
            log.debug("[SEARCH] bulk sound-counter read failed, defaulting to 0: {}", e.getMessage());
        }
        for (SoundEntity s : pending) {
            out.add(buildDoc(s, uses.getOrDefault(s.getId(), 0L)));
        }
        pending.clear();
    }

    private long flush(List<SoundSearchDocument> batch) {
        if (batch.isEmpty()) return 0;
        int n = batch.size();
        try {
            List<SoundSearchDocument> copy = List.copyOf(batch);
            EsRetry.run(() -> searchRepo.saveAll(copy), "[SEARCH] reindex sounds batch");
            return n;
        } catch (Exception e) {
            log.warn("[SEARCH] sound reindex batch failed ({} docs skipped): {}", n, e.getMessage());
            return 0;
        } finally {
            batch.clear();
        }
    }

    private static SoundSearchDocument buildDoc(SoundEntity s, long useCount) {
        return SoundSearchDocument.builder()
                .id(SoundSearchDocument.idOf(s.getId()))
                .title(s.getTitle())
                .artistName(s.getArtistName())
                .category(s.getCategory())
                .status(s.getStatus())
                .useCount(useCount)
                .createdAt(s.getCreatedAt())
                .build();
    }
}
