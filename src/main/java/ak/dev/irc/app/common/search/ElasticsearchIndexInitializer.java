package ak.dev.irc.app.common.search;

import ak.dev.irc.app.chat.search.document.ChannelSearchDocument;
import ak.dev.irc.app.chat.search.document.ChatMessageDocument;
import ak.dev.irc.app.post.search.document.PostSearchDocument;
import ak.dev.irc.app.post.search.document.SoundSearchDocument;
import ak.dev.irc.app.qna.search.document.AnswerSearchDocument;
import ak.dev.irc.app.qna.search.document.QnaSearchDocument;
import ak.dev.irc.app.research.search.document.ResearchSearchDocument;
import ak.dev.irc.app.user.search.document.UserSearchDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Ensures each {@code irc-*} Elasticsearch index exists on app boot.
 *
 * <p>The {@code @Document(createIndex = false)} on each search-document
 * class disables Spring Data's index auto-create — we want the
 * {@code RestClient} bean to come up cleanly even when ES is unreachable,
 * so the per-document init can't be allowed to fail boot. This runner
 * picks up that responsibility post-{@code ApplicationReadyEvent}, so a
 * missing ES instance still doesn't crash startup; only the index
 * provisioning is skipped (logged as WARN).</p>
 *
 * <p>Without this, a freshly-started ES with no content of a given type
 * would cause the unified {@code /api/v1/search} endpoint to fail with
 * {@code index_not_found_exception} on the first search — surfacing as a
 * misleading "Search is degraded" banner. The runner is idempotent
 * ({@code exists()}-first), safe to execute on every boot, and never
 * deletes data.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchIndexInitializer {

    private final ElasticsearchOperations esOps;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndicesExist() {
        ensureIndex(PostSearchDocument.class,       "irc-posts");
        ensureIndex(QnaSearchDocument.class,        "irc-qna");
        ensureIndex(AnswerSearchDocument.class,     "irc-answers");
        ensureIndex(ResearchSearchDocument.class,   "irc-research");
        ensureIndex(ChatMessageDocument.class,      "irc-chat-messages");
        ensureIndex(UserSearchDocument.class,       "irc-users");
        ensureIndex(ChannelSearchDocument.class,    "irc-channels");
        ensureIndex(SoundSearchDocument.class,      "irc-sounds");
    }

    private void ensureIndex(Class<?> documentClass, String label) {
        try {
            IndexOperations ops = esOps.indexOps(documentClass);
            if (ops.exists()) {
                log.info("[ES-INIT] {} already exists", label);
                return;
            }
            ops.createWithMapping();
            log.info("[ES-INIT] created {}", label);
        } catch (Exception e) {
            // ES unreachable / auth blocked / mapping conflict — log and
            // move on. The search path is degrade-safe, and the index will
            // auto-materialise on first indexAsync(...) write anyway when
            // ES does come back.
            log.warn("[ES-INIT] could not ensure {} exists: {}", label, e.getMessage());
        }
    }
}
