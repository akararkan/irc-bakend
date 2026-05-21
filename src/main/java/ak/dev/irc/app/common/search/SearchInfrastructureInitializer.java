package ak.dev.irc.app.common.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-shot, idempotent install of the Postgres search infrastructure on startup.
 *
 * <p>Scope after the Cassandra migration: this runner only manages indexes for
 * tables that <b>still live in Postgres</b> — research, questions, question
 * answers, users. Posts (and reels) moved to Cassandra and are searched via
 * Elasticsearch ({@code PostSearchService}), so post-table index attempts
 * were removed from here.</p>
 *
 * <p>Two index families per Postgres-resident corpus:
 * <ul>
 *   <li><b>GIN on {@code to_tsvector(...)}</b> — exact-token, ranked search via
 *       {@code websearch_to_tsquery} / {@code ts_rank_cd}.</li>
 *   <li><b>GIN on {@code gin_trgm_ops}</b> — typo-tolerant similarity search
 *       via the {@code %} operator. Fallback for "no FTS hits" and for
 *       prefix lookups (usernames, tags).</li>
 * </ul>
 *
 * <p>Everything is wrapped in {@code IF NOT EXISTS} so the runner is safe
 * to execute on every boot. JPA owns the column DDL; we add only function
 * indexes here.</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SearchInfrastructureInitializer {

    /**
     * Statements run on startup. {@code unaccent} is optional — wrapped in a
     * try/catch in the runner so absence on the host doesn't fail boot.
     */
    private static final List<String> EXTENSIONS = List.of(
            "CREATE EXTENSION IF NOT EXISTS pg_trgm",
            // unaccent is optional; failure is logged and ignored.
            "CREATE EXTENSION IF NOT EXISTS unaccent"
    );

    /**
     * GIN-on-tsvector indexes use the {@code 'simple'} dictionary so the
     * search is multilingual by default — Arabic, Kurdish, English all work
     * without per-locale stemming. Switch to {@code 'english'} per column if
     * stemming is desired for that field.
     */
    private static final List<String> INDEXES = List.of(
            // ── Research: title + abstract + description + keywords ──────
            "CREATE INDEX IF NOT EXISTS idx_research_fts " +
                    "ON researches USING GIN (to_tsvector('simple', " +
                    "  coalesce(title, '') || ' ' || coalesce(abstract_text, '') || ' ' || " +
                    "  coalesce(description, '') || ' ' || coalesce(keywords, '')))",
            "CREATE INDEX IF NOT EXISTS idx_research_title_trgm " +
                    "ON researches USING GIN (title gin_trgm_ops)",
            "CREATE INDEX IF NOT EXISTS idx_research_keywords_trgm " +
                    "ON researches USING GIN (keywords gin_trgm_ops)",

            // ── Questions: title + body ─────────────────────────────────
            "CREATE INDEX IF NOT EXISTS idx_question_fts " +
                    "ON questions USING GIN (to_tsvector('simple', " +
                    "  coalesce(title, '') || ' ' || coalesce(body, '')))",
            "CREATE INDEX IF NOT EXISTS idx_question_title_trgm " +
                    "ON questions USING GIN (title gin_trgm_ops)",

            // ── Question answers ────────────────────────────────────────
            "CREATE INDEX IF NOT EXISTS idx_qanswer_fts " +
                    "ON question_answers USING GIN (to_tsvector('simple', coalesce(body, '')))",

            // ── Users: username + first/last name fragments ─────────────
            // Bio lives on user_profiles now, not users — left out of the
            // composite FTS so the index doesn't reference a missing column.
            "CREATE INDEX IF NOT EXISTS idx_user_fts " +
                    "ON users USING GIN (to_tsvector('simple', " +
                    "  coalesce(username, '') || ' ' || coalesce(fname, '') || ' ' || " +
                    "  coalesce(lname, '')))",
            "CREATE INDEX IF NOT EXISTS idx_user_username_trgm " +
                    "ON users USING GIN (username gin_trgm_ops)",
            "CREATE INDEX IF NOT EXISTS idx_user_fname_trgm " +
                    "ON users USING GIN (fname gin_trgm_ops)",
            "CREATE INDEX IF NOT EXISTS idx_user_lname_trgm " +
                    "ON users USING GIN (lname gin_trgm_ops)"
    );

    @Bean
    public ApplicationRunner searchIndexInstaller(JdbcTemplate jdbc) {
        return args -> install(jdbc);
    }

    @Transactional
    public void install(JdbcTemplate jdbc) {
        for (String ext : EXTENSIONS) {
            try {
                jdbc.execute(ext);
                log.info("[SEARCH-INIT] {} OK", ext);
            } catch (Exception ex) {
                // unaccent is optional — log and keep going.
                log.warn("[SEARCH-INIT] {} skipped: {}", ext, ex.getMessage());
            }
        }
        for (String idx : INDEXES) {
            try {
                jdbc.execute(idx);
                log.info("[SEARCH-INIT] {} OK", idx.substring(0, Math.min(idx.length(), 80)));
            } catch (Exception ex) {
                log.error("[SEARCH-INIT] failed: {} — {}", idx, ex.getMessage());
            }
        }
        log.info("[SEARCH-INIT] Search infrastructure ready ({} extensions + {} indexes attempted)",
                EXTENSIONS.size(), INDEXES.size());
    }
}
