package ak.dev.irc.app.research.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ak.dev.irc.app.email.UserEmailContext;
import ak.dev.irc.app.research.dto.response.ResearchResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.dto.response.UserStatsResponse;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis cache configuration for the research module + cross-cutting social caches
 * used by the feed paths.
 *
 * <p>Cache names and TTLs:
 * <ul>
 *   <li>{@code research-by-id}      — 5 min  (evict on update / publish / delete)</li>
 *   <li>{@code research-by-slug}    — 5 min  (evict on update)</li>
 *   <li>{@code research-feed}       — 2 min  (evict on publish / delete)</li>
 *   <li>{@code trending-tags}       — 10 min (changes slowly)</li>
 *   <li>{@code user-profile}        — 5 min  (evict on profile update)</li>
 *   <li>{@code user-blocked-ids}    — 1 min  (evict on (un)block)</li>
 *   <li>{@code user-following-ids}  — 1 min  (evict on (un)follow / block)</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType(Object.class).build(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.WRAPPER_ARRAY);

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(serializer))
                .disableCachingNullValues();

        /*
         * The shared `serializer` above CANNOT round-trip a record, and this is
         * the trap to understand before adding a cache:
         *
         *   Default typing is DefaultTyping.NON_FINAL, and a Java record is
         *   implicitly FINAL. Writing therefore emits a bare object with no type
         *   header — `{"postCount":1,...}` — while reading goes through the same
         *   mapper into Object.class, which DEMANDS the As.WRAPPER_ARRAY header
         *   and throws:
         *       "Unexpected token (START_OBJECT), expected START_ARRAY: need
         *        Array value to contain `As.WRAPPER_ARRAY` type information"
         *
         *   The write succeeds, so the cache miss returns 200 and every read for
         *   the rest of the TTL is a 500 — a bug that looks intermittent and is
         *   actually total. `GET /users/{id}/stats` was doing exactly this, which
         *   is why every profile showed 0 followers: the client fell back to the
         *   denormalized counter when the live row failed.
         *
         * Non-final values (List, entity classes) get the header and are fine on
         * the shared serializer. Records are bound to their exact type instead —
         * a serializer that already knows the class needs no header, and it reads
         * back the entries the broken config left behind rather than orphaning
         * them. `user-profile` (a record too) was already fixed this way; the
         * three below were the ones still on the shared path.
         *
         * Adding a cache whose value is a record? Give it `typed(...)` here.
         */
        ObjectMapper recordMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisCacheConfiguration userProfileCache = typed(defaults, recordMapper, UserResponse.class, Duration.ofMinutes(5))
                .computePrefixWith(cacheName -> cacheName + ":v2::");

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(Map.ofEntries(
                        // ResearchResponse is a record — see `typed` above.
                        Map.entry("research-by-id",     typed(defaults, recordMapper, ResearchResponse.class, Duration.ofMinutes(5))),
                        Map.entry("research-by-slug",   typed(defaults, recordMapper, ResearchResponse.class, Duration.ofMinutes(5))),
                        Map.entry("research-feed",      defaults.entryTtl(Duration.ofMinutes(2))),
                        Map.entry("trending-tags",      defaults.entryTtl(Duration.ofMinutes(10))),
                        Map.entry("user-profile",       userProfileCache),
                        Map.entry("user-blocked-ids",   defaults.entryTtl(Duration.ofMinutes(1))),
                        Map.entry("user-following-ids", defaults.entryTtl(Duration.ofMinutes(1))),
                        // Search hits — short TTL so a hammered query collapses
                        // to one DB hit per minute while content is still fresh.
                        Map.entry("search-results",      defaults.entryTtl(Duration.ofSeconds(60))),
                        Map.entry("mention-suggestions", defaults.entryTtl(Duration.ofSeconds(30))),
                        // Hot-path cache for email-pipeline reads — collapses
                        // a fan-out burst to one DB read per recipient.
                        Map.entry("user-email-ctx",     typed(defaults, recordMapper, UserEmailContext.class, Duration.ofSeconds(60))),
                        // Profile stat row = 6 aggregate COUNTs across 3
                        // datastores on a public endpoint — 30s staleness is
                        // invisible, the query collapse is not.
                        Map.entry("user-stats",         typed(defaults, recordMapper, UserStatsResponse.class, Duration.ofSeconds(30))),
                        // Chat ephemeral-suppression gate (typing/receipts) — runs
                        // at keystroke cadence, ~5 queries cold; 10s staleness on
                        // an ephemeral signal is invisible.
                        Map.entry("chat-suppress-ephemeral", defaults.entryTtl(Duration.ofSeconds(10))),
                        // Knowledge taxonomy (topics / madhhabs) — tiny static
                        // vocabularies hit by every profile editor; they change
                        // only via migrations, so an hour of staleness is free.
                        Map.entry("knowledge-topics",   defaults.entryTtl(Duration.ofHours(1))),
                        Map.entry("knowledge-madhhabs", defaults.entryTtl(Duration.ofHours(1)))
                ))
                .build();
    }

    /**
     * A cache whose values are bound to one concrete type — the only safe way to
     * cache a {@code record} here (see the note in {@link #cacheManager}). The
     * serializer knows the class, so no polymorphic type header is written or
     * required, and entries written by the previously-broken shared serializer
     * (bare JSON objects) read back correctly instead of being orphaned.
     */
    private static <T> RedisCacheConfiguration typed(RedisCacheConfiguration base,
                                                     ObjectMapper mapper,
                                                     Class<T> type,
                                                     Duration ttl) {
        return base.entryTtl(ttl)
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, type)));
    }
}
