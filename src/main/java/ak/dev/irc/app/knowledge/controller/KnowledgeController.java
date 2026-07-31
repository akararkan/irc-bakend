package ak.dev.irc.app.knowledge.controller;

import ak.dev.irc.app.knowledge.entity.Madhhab;
import ak.dev.irc.app.knowledge.entity.Topic;
import ak.dev.irc.app.knowledge.repository.MadhhabRepository;
import ak.dev.irc.app.knowledge.repository.TopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Read-only lookup/search over the knowledge taxonomy (topics + madhhabs) —
 * the reference tables behind profile specializations and madhhab selection.
 *
 * <p>These are tiny, fixed vocabularies (dozens of rows), so search here is
 * deliberately <em>not</em> Elasticsearch: one bounded table read + an
 * in-memory contains-match across all three language columns (en/ar/ckb) is
 * O(rows) with rows ≈ constant — effectively O(1), no index to keep in sync,
 * and it works identically for Arabic and Kurdish input. Blank {@code q}
 * lists the full vocabulary (the common picker case).</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KnowledgeController {

    private final TopicRepository   topicRepository;
    private final MadhhabRepository madhhabRepository;

    /** List / search topics across nameEn / nameAr / nameCkb. */
    @GetMapping("/topics")
    public List<Topic> topics(@RequestParam(required = false) String q) {
        List<Topic> all = topicRepository.findAll();
        if (q == null || q.isBlank()) return all;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(t -> containsIgnoreCase(needle, t.getNameEn(), t.getNameAr(), t.getNameCkb()))
                .toList();
    }

    /** List / search madhhabs across nameEn / nameAr / nameCkb. */
    @GetMapping("/madhhabs")
    public List<Madhhab> madhhabs(@RequestParam(required = false) String q) {
        List<Madhhab> all = madhhabRepository.findAll();
        if (q == null || q.isBlank()) return all;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(m -> containsIgnoreCase(needle, m.getNameEn(), m.getNameAr(), m.getNameCkb()))
                .toList();
    }

    private static boolean containsIgnoreCase(String needle, String... haystacks) {
        for (String h : haystacks) {
            if (h != null && h.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }
}
