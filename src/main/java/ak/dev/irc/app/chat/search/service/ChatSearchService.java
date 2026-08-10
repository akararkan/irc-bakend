package ak.dev.irc.app.chat.search.service;

import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.chat.search.document.ChatMessageDocument;
import ak.dev.irc.app.chat.search.repository.ChatMessageSearchRepository;
import ak.dev.irc.app.common.search.EsRetry;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.data.elasticsearch.core.query.SourceFilter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch indexer + query service for chat messages. Indexing is async and
 * best-effort (never blocks the send path); the canonical store is Cassandra, so
 * a lost index write only means a message is temporarily unsearchable. Search is
 * always scoped to conversation ids the caller belongs to — the membership filter
 * is applied inside the ES query, so a user can never search a conversation they
 * are not in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSearchService {

    private static final String INDEX = "irc-chat-messages";

    /** Both query paths only read {@code messageId} — leave the bodies on the ES node. */
    private static final SourceFilter ID_ONLY_SOURCE =
            FetchSourceFilter.of(null, new String[]{"messageId"}, null);

    private final ChatMessageSearchRepository searchRepo;
    private final ElasticsearchOperations esOps;

    // ── Indexing ────────────────────────────────────────────────────────────────

    /** Index a freshly sent/edited text message. SYSTEM + empty-body messages are skipped. */
    @Async
    public void indexAsync(MessageByIdEntity m) {
        if (m == null || MessageType.SYSTEM.name().equals(m.getType()) || !StringUtils.hasText(m.getBody())) {
            return;
        }
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .id(ChatMessageDocument.idOf(m.getMessageId()))
                .messageId(m.getMessageId())
                .conversationId(m.getConversationId() == null ? null : m.getConversationId().toString())
                .senderId(m.getSenderId() == null ? null : m.getSenderId().toString())
                .body(m.getBody())
                .type(m.getType())
                .tags(m.getTags() == null || m.getTags().isEmpty() ? null : List.copyOf(m.getTags()))
                .createdAt(m.getCreatedAt())
                .build();
        try {
            EsRetry.run(() -> searchRepo.save(doc), "[CHAT-SEARCH] index " + m.getMessageId());
        } catch (Exception e) {
            log.warn("[CHAT-SEARCH] index {} failed: {}", m.getMessageId(), e.getMessage());
        }
    }

    /** Remove a message from the index (soft-delete or hard purge). */
    @Async
    public void deleteAsync(long messageId) {
        try {
            EsRetry.run(() -> searchRepo.deleteById(ChatMessageDocument.idOf(messageId)),
                    "[CHAT-SEARCH] delete " + messageId);
        } catch (Exception e) {
            log.warn("[CHAT-SEARCH] delete {} failed: {}", messageId, e.getMessage());
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────────

    /**
     * Ranked message ids matching {@code query}, restricted to {@code conversationIds}
     * (the caller's memberships). Returns an empty list when the index is missing
     * or the scope is empty; throws on a hard ES failure so the caller can fall
     * back to a bounded Cassandra scan.
     */
    public List<Long> searchMessageIds(Collection<UUID> conversationIds, String query, int size) {
        if (!StringUtils.hasText(query) || conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        List<FieldValue> scope = conversationIds.stream()
                .map(id -> FieldValue.of(id.toString())).toList();

        Query esQuery = Query.of(q -> q.bool(b -> {
            // Relevance: fuzzy best-fields on the body, plus a phrase-prefix for typeahead.
            b.must(m -> m.match(mt -> mt.field("body").query(query).fuzziness("AUTO")));
            b.should(m -> m.matchPhrasePrefix(mp -> mp.field("body").query(query).boost(1.5f)));
            // Membership scope — the caller can only ever hit their own conversations.
            b.filter(f -> f.terms(t -> t.field("conversationId").terms(tt -> tt.value(scope))));
            // Never surface system messages.
            b.mustNot(mn -> mn.term(t -> t.field("type").value(MessageType.SYSTEM.name())));
            return b;
        }));

        NativeQuery nq = NativeQuery.builder()
                .withQuery(esQuery)
                .withPageable(PageRequest.of(0, size))
                .withTrackTotalHits(false)
                .withSourceFilter(ID_ONLY_SOURCE)
                .build();

        try {
            SearchHits<ChatMessageDocument> hits = EsRetry.call(
                    () -> esOps.search(nq, ChatMessageDocument.class, IndexCoordinates.of(INDEX)),
                    "[CHAT-SEARCH] query");
            return hits.stream().map(h -> h.getContent().getMessageId()).filter(java.util.Objects::nonNull).toList();
        } catch (Exception e) {
            if (EsRetry.isIndexNotFound(e)) {
                log.debug("[CHAT-SEARCH] empty (index not created yet)");
                return List.of();
            }
            throw e; // hard failure → caller falls back to a bounded Cassandra scan
        }
    }

    /**
     * Message ids of posts carrying an exact {@code #tag} (lowercased, no '#'),
     * newest first, scoped to the caller's conversations. Same failure contract
     * as {@link #searchMessageIds}: empty on missing index, throws on hard
     * failure so the caller can fall back to a bounded scan.
     */
    public List<Long> searchIdsByTag(Collection<UUID> conversationIds, String tag, int size) {
        if (!StringUtils.hasText(tag) || conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        List<FieldValue> scope = conversationIds.stream()
                .map(id -> FieldValue.of(id.toString())).toList();

        Query esQuery = Query.of(q -> q.bool(b -> {
            b.must(m -> m.term(t -> t.field("tags").value(tag)));
            b.filter(f -> f.terms(t -> t.field("conversationId").terms(tt -> tt.value(scope))));
            return b;
        }));

        NativeQuery nq = NativeQuery.builder()
                .withQuery(esQuery)
                .withSort(s -> s.field(f -> f.field("messageId")
                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                .withPageable(PageRequest.of(0, size))
                .withTrackTotalHits(false)
                .withSourceFilter(ID_ONLY_SOURCE)
                .build();

        try {
            SearchHits<ChatMessageDocument> hits = EsRetry.call(
                    () -> esOps.search(nq, ChatMessageDocument.class, IndexCoordinates.of(INDEX)),
                    "[CHAT-SEARCH] tag query");
            return hits.stream().map(h -> h.getContent().getMessageId()).filter(java.util.Objects::nonNull).toList();
        } catch (Exception e) {
            if (EsRetry.isIndexNotFound(e)) return List.of();
            throw e;
        }
    }
}
