package ak.dev.irc.dbexport;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.schema.Action;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-shot schema exporter for Hibernate 7.
 *
 * <p>Walks {@code src/main/java/ak/dev/irc}, loads every class carrying a JPA
 * mapping annotation, builds Hibernate metadata, and asks
 * {@link SchemaManagementToolCoordinator} to write a PostgreSQL CREATE script
 * to {@code target/generated-schema.sql}. No JDBC connection is opened —
 * {@code database.action=none} keeps it offline.</p>
 */
class SchemaExporter {

    @Test
    void exportSchema() throws Exception {
        Path output = Path.of("target", "generated-schema.sql");
        Files.createDirectories(output.getParent());
        Files.deleteIfExists(output);

        Map<String, Object> settings = new HashMap<>();
        settings.put(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        settings.put(AvailableSettings.HBM2DDL_CHARSET_NAME, "UTF-8");
        settings.put(AvailableSettings.FORMAT_SQL, "true");
        settings.put(AvailableSettings.SHOW_SQL, "false");

        // JPA-standard script-only schema generation — no database touched.
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, Action.NONE.getExternalJpaName());
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_ACTION, Action.CREATE_ONLY.getExternalJpaName());
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_CREATE_TARGET,
                output.toAbsolutePath().toString());

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();

        try {
            MetadataSources sources = new MetadataSources(registry);
            List<Class<?>> entities = scanEntities();
            entities.forEach(sources::addAnnotatedClass);
            System.out.println("Discovered " + entities.size() + " mapped classes");

            Metadata metadata = sources.buildMetadata();

            SchemaManagementToolCoordinator.process(
                    metadata,
                    registry,
                    settings,
                    action -> { /* no-op delayed-drop registry */ });

        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }

        System.out.println("Schema written to " + output.toAbsolutePath());
    }

    private static List<Class<?>> scanEntities() throws IOException {
        Path root = Path.of("src", "main", "java", "ak", "dev", "irc");
        List<Class<?>> hits = new ArrayList<>();

        try (Stream<Path> walker = Files.walk(root)) {
            walker.filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> {
                      String rel = Path.of("src", "main", "java")
                                       .relativize(p)
                                       .toString()
                                       .replace('/', '.')
                                       .replace('\\', '.');
                      String fqn = rel.substring(0, rel.length() - ".java".length());
                      try {
                          Class<?> cls = Class.forName(fqn);
                          if (hasAnyAnnotation(cls,
                                  Entity.class, Embeddable.class, MappedSuperclass.class)) {
                              hits.add(cls);
                          }
                      } catch (Throwable ignored) {
                          // Inner classes, unloadable types — skip
                      }
                  });
        }
        return hits;
    }

    @SafeVarargs
    private static boolean hasAnyAnnotation(Class<?> cls, Class<? extends Annotation>... anns) {
        for (var ann : anns) if (cls.isAnnotationPresent(ann)) return true;
        return false;
    }
}
