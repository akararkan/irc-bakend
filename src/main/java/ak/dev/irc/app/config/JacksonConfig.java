package ak.dev.irc.app.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson timezone fix.
 *
 * <p>The bug this fixes — {@code LocalDateTime} has no zone, so the default
 * {@link JavaTimeModule} serializes it as plain ISO without a {@code Z}
 * suffix (e.g. {@code "2026-05-15T11:00:00.123"}). Browsers parse strings
 * with no zone marker as <strong>local time</strong>, which produces a
 * "3 hours ago" / "in 3 hours" offset for users outside UTC.</p>
 *
 * <p>The fix — every {@code LocalDateTime} we write to the wire is forced to
 * the {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} pattern. The {@code Z} marker
 * tells the browser the value is UTC, so {@code new Date(s)} resolves to
 * the correct absolute moment regardless of the user's timezone.</p>
 *
 * <p>Pair this with {@code spring.jpa.properties.hibernate.jdbc.time_zone:
 * UTC} (set in {@code application.yaml}) so the database side of the round
 * trip is also UTC. Without that, a non-UTC server would write the wrong
 * absolute instant despite the {@code Z} suffix.</p>
 *
 * <p>We use {@link Jackson2ObjectMapperBuilderCustomizer} (not a custom
 * {@code ObjectMapper} bean) so Spring Boot's other auto-configured
 * settings — non_null inclusion, modules, etc. — are preserved.</p>
 */
@Configuration
class JacksonConfig {

    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcDateTimeCustomizer() {
        return builder -> builder
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(UTC_ISO))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(UTC_ISO));
    }
}
