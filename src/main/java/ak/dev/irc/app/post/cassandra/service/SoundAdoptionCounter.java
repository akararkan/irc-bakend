package ak.dev.irc.app.post.cassandra.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Day-bucketed sound adoption counter (sound-library.md §7): one counter row
 * per (day, sound) bumped every time a post/reel uses the sound. Powers the
 * admin "trending sounds" board — usage-based over a rolling window, the same
 * philosophy as tag trending. Fail-open: adoption telemetry never fails the
 * post create that triggered it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoundAdoptionCounter {

    private final CqlOperations cqlOperations;

    private volatile boolean ready = false;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTable() {
        try {
            cqlOperations.execute("""
                    CREATE TABLE IF NOT EXISTS sound_adoptions_by_day (
                        day text,
                        sound_id uuid,
                        uses counter,
                        PRIMARY KEY (day, sound_id)
                    )""");
            ready = true;
        } catch (Exception e) {
            log.warn("[SOUND-ADOPT] sound_adoptions_by_day ensure failed: {}", e.getMessage());
        }
    }

    public void bump(UUID soundId) {
        if (!ready || soundId == null) return;
        try {
            cqlOperations.execute(
                    "UPDATE sound_adoptions_by_day SET uses = uses + 1 WHERE day = ? AND sound_id = ?",
                    LocalDate.now(ZoneOffset.UTC).toString(), soundId);
        } catch (Exception e) {
            log.debug("[SOUND-ADOPT] bump failed for {}: {}", soundId, e.getMessage());
        }
    }

    /** Top sounds by adoption over the last {@code days} (≤14), merged across day partitions. */
    public List<Map.Entry<UUID, Long>> top(int days, int limit) {
        Map<UUID, Long> merged = new HashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int span = Math.max(1, Math.min(days, 14));
        for (int i = 0; i < span; i++) {
            try {
                var rows = cqlOperations.queryForList(
                        "SELECT sound_id, uses FROM sound_adoptions_by_day WHERE day = ?",
                        today.minusDays(i).toString());
                for (Map<String, Object> row : rows) {
                    Object id = row.get("sound_id");
                    Object uses = row.get("uses");
                    if (id instanceof UUID uuid && uses instanceof Number n) {
                        merged.merge(uuid, n.longValue(), Long::sum);
                    }
                }
            } catch (Exception e) {
                log.debug("[SOUND-ADOPT] day read failed: {}", e.getMessage());
            }
        }
        List<Map.Entry<UUID, Long>> out = new ArrayList<>(merged.entrySet());
        out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
