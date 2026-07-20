package ak.dev.irc.app.research.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ak.dev.irc.app.user.dto.response.UserResponse;
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

        ObjectMapper userProfileMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Jackson2JsonRedisSerializer<UserResponse> userProfileSerializer =
                new Jackson2JsonRedisSerializer<>(userProfileMapper, UserResponse.class);

        RedisCacheConfiguration userProfileCache = defaults
                .entryTtl(Duration.ofMinutes(5))
                .computePrefixWith(cacheName -> cacheName + ":v2::")
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(userProfileSerializer));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(Map.ofEntries(
                        Map.entry("research-by-id",     defaults.entryTtl(Duration.ofMinutes(5))),
                        Map.entry("research-by-slug",   defaults.entryTtl(Duration.ofMinutes(5))),
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
                        Map.entry("user-email-ctx",     defaults.entryTtl(Duration.ofSeconds(60))),
                        // Profile stat row = 6 aggregate COUNTs across 3
                        // datastores on a public endpoint — 30s staleness is
                        // invisible, the query collapse is not.
                        Map.entry("user-stats",         defaults.entryTtl(Duration.ofSeconds(30)))
                ))
                .build();
    }
}
