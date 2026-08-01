package ak.dev.irc.app.settings.privacy.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;

/**
 * Normalizes keywords so a hidden-keyword filter (spec §13) can't be trivially
 * bypassed by orthography — which matters most in exactly the languages this
 * platform serves (Arabic, Kurdish). Normalization:
 *
 * <ol>
 *   <li>Unicode NFKC then strip combining marks (diacritics / tashkeel).</li>
 *   <li>Case-fold to lower case.</li>
 *   <li>Unify Arabic/Kurdish letter variants:
 *       {@code ي/ی → ي}, {@code ك/ک → ك}, {@code ة/ه → ه}, and the alef
 *       variants {@code أإآ → ا}.</li>
 *   <li>Collapse whitespace.</li>
 * </ol>
 */
@Component
public class KeywordNormalizer {

    public String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";

        // 1. NFKC + strip combining marks (diacritics, Arabic tashkeel U+064B–U+0652, tatweel).
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = s.replaceAll("[\\p{Mn}\\p{Me}]", "");
        s = s.replace("ـ", ""); // Arabic tatweel (kashida)

        // 2. Case-fold.
        s = s.toLowerCase();

        // 3. Unify Arabic/Kurdish letter variants.
        s = s
                .replace('ی', 'ي')   // Farsi/Kurdish yeh ی → Arabic yeh ي
                .replace('ى', 'ي')   // alef maqsura ى → yeh ي
                .replace('ک', 'ك')   // keheh ک → kaf ك
                .replace('ة', 'ه')   // teh marbuta ة → heh ه
                .replace('أ', 'ا')   // alef with hamza above أ → alef ا
                .replace('إ', 'ا')   // alef with hamza below إ → alef ا
                .replace('آ', 'ا');  // alef madda آ → alef ا

        // 4. Collapse internal whitespace.
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /**
     * True when {@code text} contains any of the (already-normalized) hidden
     * keywords. Callers pass the user's normalized keyword set; this normalizes
     * the candidate text once and does a contains-scan.
     */
    public boolean matchesAny(String text, Iterable<String> normalizedKeywords) {
        if (text == null || normalizedKeywords == null) return false;
        String hay = normalize(text);
        if (hay.isEmpty()) return false;
        for (String kw : normalizedKeywords) {
            if (kw != null && !kw.isEmpty() && hay.contains(kw)) return true;
        }
        return false;
    }
}
