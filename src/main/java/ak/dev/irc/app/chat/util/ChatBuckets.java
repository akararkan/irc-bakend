package ak.dev.irc.app.chat.util;

/**
 * Cassandra partition bucketing for the message log.
 *
 * <p>A Cassandra partition must stay bounded (rule of thumb: &lt; ~100&nbsp;MB and
 * &lt; ~100k rows). A busy conversation with millions of messages in one partition
 * would rot read latency, so each conversation's history is split into fixed
 * time windows of {@link #BUCKET_DAYS} days. The partition key is therefore
 * {@code (conversation_id, bucket)} and no single partition can grow without
 * bound.</p>
 *
 * <p>The bucket is derived purely from the message's Snowflake timestamp, so the
 * <b>writer and reader compute the identical bucket with no stored coupling and
 * no extra lookup</b> — the reader knows exactly which partition a cursor lives
 * in. See {@link SnowflakeIdGenerator#timestampOf(long)}.</p>
 */
public final class ChatBuckets {

    /**
     * Days per partition window. 10 days keeps even a very active DM's partition
     * comfortably bounded while a quiet chat still walks only a handful of empty
     * buckets when scrolling back. Tunable down for extreme-traffic conversations
     * without any data migration — only newly written rows land in finer buckets,
     * and the reader's {@code [minBucket..maxBucket]} walk still terminates.
     */
    public static final int BUCKET_DAYS = 10;

    private static final long MS_PER_DAY = 86_400_000L;

    private ChatBuckets() {}

    /** Bucket for a Snowflake message id. */
    public static int bucketOf(long messageId) {
        return bucketForTimestamp(SnowflakeIdGenerator.timestampOf(messageId));
    }

    /** Bucket for a raw epoch-millis timestamp (used for the {@code created_at} floor). */
    public static int bucketForTimestamp(long epochMillis) {
        long days = epochMillis / MS_PER_DAY;
        return (int) (days / BUCKET_DAYS);
    }

    /** Bucket for the current wall clock — the newest partition to write into. */
    public static int currentBucket() {
        return bucketForTimestamp(System.currentTimeMillis());
    }
}
