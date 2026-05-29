package ak.dev.irc.app.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.autoconfigure.elasticsearch.RestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Tunes the low-level Elasticsearch {@code RestClient}'s pooled HTTP
 * connections so they never outlive the server side (which caused the
 * intermittent {@code Connection is closed} warnings on the global search
 * path and the async indexer).
 *
 * <h3>What this fixes</h3>
 * <p>The auto-configured {@code RestClient} keeps connections alive
 * indefinitely by default — it has no idle-eviction and trusts whatever
 * {@code Keep-Alive} header the server sends back. When ES (or a fronting
 * load balancer) silently closes an idle socket after its own timeout, the
 * client's next request picks up the dead connection and throws
 * {@code ConnectionClosedException} before any retry kicks in.</p>
 *
 * <h3>Why this used to look like it wasn't working</h3>
 * <p>The earlier version of this customizer was implemented as a lambda on
 * {@link RestClientBuilderCustomizer#customize(RestClientBuilder)} that called
 * {@code setHttpClientConfigCallback(...)} inside. Spring Boot
 * <strong>already</strong> sets a {@code HttpClientConfigCallback} on the
 * {@code RestClientBuilder} — it's the chain that wires up authentication,
 * SSL, and dispatches through the rest of the configured customizers. By
 * calling {@code setHttpClientConfigCallback} ourselves we silently
 * <strong>replaced</strong> Boot's chain, so auth headers / SSL setup were
 * dropped <em>and</em> our pool tuning never reached any customizer that
 * ordered after us. Symptom: ES closed the connection (auth failure or
 * malformed SSL handshake) → {@code Connection is closed}, and even the
 * retry hit the same broken pool.</p>
 *
 * <p>The fix is to use the {@code customize(HttpAsyncClientBuilder)} default
 * method — Boot invokes it from inside its own {@code HttpClientConfigCallback}
 * chain. Auth and SSL stay intact, and our pool tuning composes with them.</p>
 *
 * <h3>What this does</h3>
 * <ul>
 *   <li>Hard-caps every pooled connection's lifetime at <strong>1 minute</strong>
 *       ({@code setConnectionTimeToLive}) — old enough to amortise the TCP
 *       handshake, young enough that a fronting LB won't have killed it.</li>
 *   <li>When the server doesn't send a {@code Keep-Alive: timeout=…} header,
 *       falls back to a finite <strong>15 s</strong> per-connection keep-alive
 *       instead of the Apache default ("forever").</li>
 * </ul>
 *
 * <p>Combined with the single retry in {@code EsRetry.run / call}, this gives
 * belt + braces — the pool stays fresh, and any race that still slips through
 * self-heals.</p>
 */
@Slf4j
@Configuration
public class ElasticsearchConfig {

    /**
     * How long a pooled HTTP connection may live before forced eviction.
     * Dropped from 2 min to 1 min after still seeing {@code Connection is
     * closed} warnings in prod — Railway / Cloudflare in front of ES often
     * close idle sockets faster than 60 s.
     */
    private static final long CONNECTION_TTL_MINUTES = 1;

    /**
     * Fallback keep-alive when the server omits the {@code Keep-Alive} header.
     * Conservative 15 s so we recycle well before any reasonable LB / proxy
     * idle timeout.
     */
    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 15;

    @Bean
    public RestClientBuilderCustomizer elasticsearchPoolHygieneCustomizer() {
        return new RestClientBuilderCustomizer() {

            /**
             * Top-level builder hook — intentionally a no-op. Setting
             * {@code httpClientConfigCallback} here would clobber Boot's
             * default callback (auth + SSL + customizer dispatch).
             */
            @Override
            public void customize(RestClientBuilder builder) {
                // no-op — see class javadoc
            }

            /**
             * HTTP client hook — Boot invokes this from inside its own
             * {@code HttpClientConfigCallback} chain, so auth + SSL stay
             * intact and our pool tuning composes cleanly.
             */
            @Override
            public void customize(HttpAsyncClientBuilder http) {
                http.setKeepAliveStrategy(keepAliveStrategy())
                    .setConnectionTimeToLive(CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
                log.info("[ES] pool tuning applied — connectionTTL={}min, fallbackKeepAlive={}s",
                        CONNECTION_TTL_MINUTES, DEFAULT_KEEP_ALIVE_SECONDS);
            }
        };
    }

    /**
     * Honours an explicit {@code Keep-Alive: timeout=…} from the server, else
     * falls back to {@link #DEFAULT_KEEP_ALIVE_SECONDS}. Apache's default
     * (when no header is present) is "keep forever", which is what produces
     * the silent-close races.
     */
    private static ConnectionKeepAliveStrategy keepAliveStrategy() {
        return (response, context) -> {
            HeaderElementIterator it = new BasicHeaderElementIterator(
                    response.headerIterator(HTTP.CONN_KEEP_ALIVE));
            while (it.hasNext()) {
                HeaderElement el = it.nextElement();
                String name = el.getName();
                String value = el.getValue();
                if (value != null && "timeout".equalsIgnoreCase(name)) {
                    try {
                        return Long.parseLong(value) * 1000L;
                    } catch (NumberFormatException ignored) {
                        // fall through to the default
                    }
                }
            }
            return DEFAULT_KEEP_ALIVE_SECONDS * 1000L;
        };
    }
}
