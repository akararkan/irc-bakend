package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wires each {@link ModerationApplier} to the entity type it handles.
 *
 * <p>Spring collects every applier bean on the classpath, so adding a new
 * moderated surface is one class and no registration step. A type with no
 * applier is legal — ephemeral surfaces like live chat decide inline and have no
 * row to flip later — but a <em>duplicate</em> registration is a bug worth
 * failing on, since it would be silently non-deterministic which one publishes
 * the content.</p>
 *
 * <p><b>Resolution is deferred to first use, and that is not an optimisation.</b>
 * There is a genuine dependency cycle here:
 * {@code ContentModerationService → this registry → PostModerationApplier →
 * CassandraPostService → ContentModerationService}. Spring Boot 3 rejects
 * circular references by default, so resolving the appliers in the constructor
 * would fail the context at startup. Holding the {@link ObjectProvider} and
 * resolving on the first call breaks the cycle at bean-creation time while
 * keeping the wiring automatic — by the time a verdict needs applying, every
 * bean in the graph already exists.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationApplierRegistry {

    private final ObjectProvider<ModerationApplier> discovered;

    private volatile Map<ModeratedEntityType, ModerationApplier> appliers;

    public Optional<ModerationApplier> forType(ModeratedEntityType type) {
        return Optional.ofNullable(resolved().get(type));
    }

    /** Entity types with a deferred-verdict path — used by the ops/docs endpoints. */
    public Set<ModeratedEntityType> covered() {
        return resolved().keySet();
    }

    private Map<ModeratedEntityType, ModerationApplier> resolved() {
        Map<ModeratedEntityType, ModerationApplier> local = appliers;
        if (local != null) return local;

        synchronized (this) {
            if (appliers != null) return appliers;

            Map<ModeratedEntityType, ModerationApplier> built =
                    new EnumMap<>(ModeratedEntityType.class);
            for (ModerationApplier applier : discovered.orderedStream().toList()) {
                ModerationApplier previous = built.put(applier.supports(), applier);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Two ModerationAppliers claim %s: %s and %s".formatted(
                                    applier.supports(),
                                    previous.getClass().getName(),
                                    applier.getClass().getName()));
                }
            }
            log.info("[MODERATION] {} appliers registered: {}", built.size(), built.keySet());
            appliers = built;
            return built;
        }
    }
}
