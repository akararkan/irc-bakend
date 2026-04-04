package ak.dev.irc.app.research.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis cache configuration for the research module.
 *
 * <p>Cache names and TTLs:
 * <ul>
 *   <li>{@code research-by-id}   — 5 min  (evict on update / publish / delete)</li>
 *   <li>{@code research-by-slug} — 5 min  (evict on update)</li>
 *   <li>{@code research-feed}    — 2 min  (evict on publish / delete)</li>
 *   <li>{@code trending-tags}    — 10 min (changes slowly)</li>
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
                        JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(serializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(Map.of(
                        "research-by-id",   defaults.entryTtl(Duration.ofMinutes(5)),
                        "research-by-slug", defaults.entryTtl(Duration.ofMinutes(5)),
                        "research-feed",    defaults.entryTtl(Duration.ofMinutes(2)),
                        "trending-tags",    defaults.entryTtl(Duration.ofMinutes(10))
                ))
                .build();
    }
}
