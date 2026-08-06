package ak.dev.irc.app.common.tag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

/**
 * Counter writes for {@code tag_counters}. Cassandra counter columns can only be
 * mutated by {@code UPDATE … SET col = col + N}, so Spring Data {@code save()}
 * cannot be used — this issues raw CQL (same approach as the post
 * {@code CounterService}).
 *
 * <p>Counters are not idempotent — callers must guarantee a tag is counted
 * exactly once per content (tag on create, decrement on delete/retag).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagCounterService {

    private final CqlOperations cqlOperations;

    public void increment(String scope, String tag) { add(scope, tag, 1); }
    public void decrement(String scope, String tag) { add(scope, tag, -1); }

    /** Bulk delta — used by the admin tag-merge counter transfer. */
    public void addDelta(String scope, String tag, long delta) { add(scope, tag, delta); }

    private void add(String scope, String tag, long delta) {
        cqlOperations.execute(
                "UPDATE tag_counters SET usage_count = usage_count + ? WHERE scope = ? AND tag = ?",
                delta, scope, tag);
    }
}
