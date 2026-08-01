package ak.dev.irc.app.media.service;

import org.springframework.stereotype.Component;

/**
 * Malware / NSFW scan hook (spec §20.4c). External scanning services are out of
 * scope for the offline build, so the default {@link AllowAllScanner} passes
 * everything while keeping the {@code FAILED_MODERATION} path real — a production
 * scanner drops in behind this interface.
 */
public interface MediaScanner {

    /** @return true if the asset is safe to publish. */
    boolean isClean(byte[] content, String mime);

    @Component
    class AllowAllScanner implements MediaScanner {
        @Override
        public boolean isClean(byte[] content, String mime) {
            return true;
        }
    }
}
