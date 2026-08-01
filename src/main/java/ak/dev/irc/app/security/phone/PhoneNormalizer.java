package ak.dev.irc.app.security.phone;

import ak.dev.irc.app.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Normalises phone numbers to <b>E.164</b> (spec §2, §3) so {@code 07501234567}
 * and {@code +9647501234567} never create two accounts.
 *
 * <p>The reference design uses Google libphonenumber; this build is offline
 * (pure-JDK, no new dependencies), so this is a deliberately conservative
 * normaliser: it handles the common shapes (leading {@code +}, international
 * {@code 00} prefix, and a national number with a trunk {@code 0} that is
 * rewritten with the configured default country calling code). It validates the
 * E.164 length band (E.164 allows at most 15 digits). It does NOT do per-country
 * length/prefix validation — swap in libphonenumber when a dependency can be
 * added.</p>
 */
@Component
public class PhoneNormalizer {

    /** Default country calling code (digits only, no +). Iraq = 964. */
    private final String defaultCallingCode;

    public PhoneNormalizer(@Value("${app.phone.default-calling-code:964}") String defaultCallingCode) {
        this.defaultCallingCode = defaultCallingCode == null ? "964" : defaultCallingCode.trim();
    }

    /**
     * @return the E.164 form, e.g. {@code +9647501234567}.
     * @throws BadRequestException when the input cannot be coerced to a plausible E.164 number.
     */
    public String toE164(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Phone number is required.", "PHONE_REQUIRED");
        }
        String s = raw.trim();
        boolean hadPlus = s.startsWith("+");

        // Strip everything except digits (drops spaces, dashes, parens, dots).
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw new BadRequestException("Phone number has no digits.", "PHONE_INVALID");
        }

        String e164Digits;
        if (hadPlus) {
            e164Digits = digits;                       // already international
        } else if (digits.startsWith("00")) {
            e164Digits = digits.substring(2);          // 00<cc>… international prefix
        } else if (digits.startsWith("0")) {
            e164Digits = defaultCallingCode + digits.substring(1);  // national, drop trunk 0
        } else if (digits.startsWith(defaultCallingCode)) {
            e164Digits = digits;                       // already carries the country code
        } else {
            // Bare national number without a trunk 0 — assume default country.
            e164Digits = defaultCallingCode + digits;
        }

        if (e164Digits.length() < 8 || e164Digits.length() > 15) {
            throw new BadRequestException(
                    "Phone number is not a valid E.164 length.", "PHONE_INVALID");
        }
        return "+" + e164Digits;
    }

    /** Non-throwing variant — returns null instead of raising. */
    public String toE164OrNull(String raw) {
        try {
            return toE164(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
