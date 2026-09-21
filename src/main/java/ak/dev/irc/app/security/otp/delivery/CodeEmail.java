package ak.dev.irc.app.security.otp.delivery;

/**
 * The HTML body shared by every "here is your code" email (phone verification,
 * email verification).
 *
 * <p>Deliberately plain markup — no images, no remote CSS, no CTA button.
 * Verification codes are read on phones, frequently straight from a notification
 * preview, so the code has to be the first substantial thing in the message and
 * legible before anything loads. It is also the one email class that must never
 * contain a link: training users to click through from a message carrying a
 * one-time code is exactly the phishing shape we don't want them used to.</p>
 */
public final class CodeEmail {

    private CodeEmail() {}

    public static String html(String heading, String intro, String code, long ttlMinutes) {
        return """
                <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\
                max-width:480px;margin:0 auto;padding:32px 24px;color:#111">
                  <h2 style="margin:0 0 8px;font-size:18px;font-weight:600">%s</h2>
                  <p style="margin:0 0 24px;font-size:14px;color:#555">%s</p>
                  <div style="font-size:34px;font-weight:700;letter-spacing:8px;\
                background:#f4f4f5;border-radius:10px;padding:18px 12px;text-align:center">%s</div>
                  <p style="margin:24px 0 0;font-size:13px;color:#555">
                    It expires in %s minutes. If you didn't request it, ignore this email —
                    someone may have typed your address by mistake. Never share this code.
                  </p>
                </div>"""
                .formatted(heading, intro, code, ttlMinutes);
    }
}
