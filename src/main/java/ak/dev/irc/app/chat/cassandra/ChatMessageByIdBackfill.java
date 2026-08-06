package ak.dev.irc.app.chat.cassandra;

import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.util.ChatBuckets;
import ak.dev.irc.app.chat.util.SnowflakeIdGenerator;
import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;

/**
 * One-time, self-healing backfill of {@code message_by_id} from
 * {@code messages_by_conversation}.
 *
 * <p><b>Why this exists.</b> A pre-fix build created the {@code message_by_id}
 * primary-key column as {@code messageid} (Spring Data Cassandra lowercases a
 * camelCase property, it does not snake_case it). During that window a send wrote
 * the timeline row ({@code messages_by_conversation}, whose columns were always
 * correct) and then failed to write its {@code message_by_id} twin — leaving
 * "zombie" messages that appear in the timeline but can't be reacted to, edited,
 * deleted, or opened by id (they surface as {@code MESSAGE_NOT_FOUND}).
 * {@link ChatCassandraSchemaInitializer} repairs the column; this backfills the
 * rows that were lost while it was wrong.</p>
 *
 * <p><b>Safe + zero-touch.</b> It runs once, guarded by a marker row in
 * {@code chat_migrations}, so there is no per-boot cost and nothing to configure.
 * It writes <b>only rows that are missing</b> — a healthy {@code message_by_id}
 * row is never overwritten, so a message concurrently edited or deleted by live
 * traffic can never be clobbered with a stale copy (zombies, by definition, have
 * no {@code message_by_id} row and so are never reachable by edit/delete). It
 * reads only within bounded, known bucket ranges (from the Postgres conversation's
 * created time up to the current bucket), never a blind table scan, and pages
 * through conversations so it never loads the whole table into memory.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageByIdBackfill {

    /** Bump the suffix to force a re-run after a future data issue. */
    private static final String MARKER = "message_by_id_backfill_v1";
    private static final int PAGE = 500;
    private static final int CONV_PAGE = 200;
    /** Safety valve only — the cursor strictly decreases so a bucket always
     *  terminates naturally; if this is ever hit the run is treated as incomplete
     *  and NOT marked done, so nothing is silently truncated. */
    private static final int MAX_PAGES_PER_BUCKET = 200_000;

    private final CqlSession session;
    private final ConversationRepository conversationRepo;
    private final MessageByConversationRepository messageRepo;
    private final MessageByIdRepository messageByIdRepo;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    // Run after the schema initializer's @PostConstruct column-repair has happened.
    @Order(100)
    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            session.execute("CREATE TABLE IF NOT EXISTS " + keyspace
                    + ".chat_migrations (id text PRIMARY KEY, applied_at timestamp)");
            boolean alreadyDone = session.execute(
                    "SELECT id FROM " + keyspace + ".chat_migrations WHERE id = ?", MARKER).one() != null;
            if (alreadyDone) {
                log.debug("[CHAT-BACKFILL] {} already applied — skipping", MARKER);
                return;
            }

            long scanned = 0, rebuilt = 0;
            boolean truncated = false;
            // Page through conversations rather than findAll() so a large deployment
            // never loads the whole table into memory for this one-time repair.
            int page = 0;
            org.springframework.data.domain.Page<Conversation> pg;
            do {
                pg = conversationRepo.findAll(org.springframework.data.domain.PageRequest.of(page++, CONV_PAGE));
                for (Conversation c : pg.getContent()) {
                    long[] counts = backfillConversation(c);
                    scanned += counts[0];
                    rebuilt += counts[1];
                    if (counts[2] == 1) truncated = true;
                }
            } while (pg.hasNext());

            if (truncated) {
                // A partition exceeded the safety cap — leave the marker UNSET so the
                // un-rebuilt rows are retried on a later boot (never silently skipped).
                log.error("[CHAT-BACKFILL] {} incomplete — a partition exceeded the page cap; "
                        + "NOT marking done (will retry). scanned={} rebuilt={}", MARKER, scanned, rebuilt);
                return;
            }
            // LWT so concurrent nodes on first boot don't double-mark; the run itself
            // is idempotent (only-missing rows are written) so a concurrent run is safe too.
            session.execute("INSERT INTO " + keyspace
                    + ".chat_migrations (id, applied_at) VALUES (?, toTimestamp(now())) IF NOT EXISTS", MARKER);
            log.info("[CHAT-BACKFILL] {} complete — scanned {} timeline messages, rebuilt {} missing message_by_id rows",
                    MARKER, scanned, rebuilt);
        } catch (Throwable t) {
            // Catch Throwable (not just Exception) so a resource error can never hard-fail
            // application startup; leave the marker unset so a later healthy boot retries.
            log.warn("[CHAT-BACKFILL] {} did not complete (will retry next boot): {}", MARKER, t.toString());
        }
    }

    /** Admin re-run support: drop the completion marker so {@link #backfill()}
     *  executes again. The walk itself is idempotent (only-missing writes). */
    public void clearMarker() {
        try {
            session.execute("DELETE FROM " + keyspace + ".chat_migrations WHERE id = ?", MARKER);
        } catch (Exception e) {
            log.warn("[CHAT-BACKFILL] marker clear failed: {}", e.getMessage());
        }
    }

    /** @return {@code [scanned, rebuilt, truncatedFlag]} for one conversation. */
    private long[] backfillConversation(Conversation c) {
        long scanned = 0, rebuilt = 0;
        boolean truncated = false;
        int minBucket = c.getCreatedAt() != null
                ? ChatBuckets.bucketForTimestamp(c.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli())
                : ChatBuckets.bucketForTimestamp(SnowflakeIdGenerator.CUSTOM_EPOCH);
        // Walk up to the CURRENT bucket, not bucketOf(lastMessageId): a zombie's send
        // failed BEFORE advanceLastMessage ran, so lastMessageId does not point at it —
        // it can sit in a newer bucket than the last "successful" message.
        int maxBucket = ChatBuckets.currentBucket();
        if (c.getLastMessageId() != null) {
            maxBucket = Math.max(maxBucket, ChatBuckets.bucketOf(c.getLastMessageId()));
        }

        for (int bucket = maxBucket; bucket >= minBucket; bucket--) {
            Long cursor = null;
            int p = 0;
            while (true) {
                if (p++ >= MAX_PAGES_PER_BUCKET) { truncated = true; break; }
                List<MessageByConversationEntity> rows = (cursor == null)
                        ? messageRepo.firstPage(c.getId(), bucket, PAGE)
                        : messageRepo.pageBefore(c.getId(), bucket, cursor, PAGE);
                if (rows.isEmpty()) break;
                // Existence check batched per page (chunked IN) — previously one
                // existsById round-trip per scanned row.
                java.util.Set<Long> present = new java.util.HashSet<>();
                List<Long> pageIds = rows.stream().map(MessageByConversationEntity::getMessageId).toList();
                for (int i = 0; i < pageIds.size(); i += 100) {
                    for (MessageByIdEntity e : messageByIdRepo.findAllByMessageIdIn(
                            pageIds.subList(i, Math.min(i + 100, pageIds.size())))) {
                        present.add(e.getMessageId());
                    }
                }
                for (MessageByConversationEntity r : rows) {
                    scanned++;
                    // Only fill MISSING (zombie) rows. A row that already exists may be
                    // concurrently edited/deleted by live traffic — never clobber it with
                    // the stale copy we read a moment ago.
                    if (present.contains(r.getMessageId())) continue;
                    messageByIdRepo.save(toById(r));
                    rebuilt++;
                }
                cursor = rows.get(rows.size() - 1).getMessageId();
                if (rows.size() < PAGE) break;
            }
        }
        return new long[]{scanned, rebuilt, truncated ? 1 : 0};
    }

    private static MessageByIdEntity toById(MessageByConversationEntity r) {
        return MessageByIdEntity.builder()
                .messageId(r.getMessageId())
                .conversationId(r.getConversationId())
                .bucket(r.getBucket())
                .senderId(r.getSenderId())
                .type(r.getType())
                .body(r.getBody())
                .media(r.getMedia())
                .replyToId(r.getReplyToId())
                .forwardedFrom(r.getForwardedFrom())
                .mentions(r.getMentions())
                .deleted(r.getDeleted())
                .editedAt(r.getEditedAt())
                .systemEvent(r.getSystemEvent())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
