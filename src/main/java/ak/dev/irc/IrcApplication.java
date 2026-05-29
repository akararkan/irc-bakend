package ak.dev.irc;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class IrcApplication {

	public static void main(String[] args) {
		// Pin the JVM to UTC BEFORE any Spring bean is instantiated so every
		// LocalDateTime.now() / Date / Calendar in the app produces UTC values.
		// Pairs with hibernate.jdbc.time_zone=UTC so reads + writes match.
		System.setProperty("user.timezone", "UTC");
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

		// Boot-time env probe — prints exactly what the container sees for the
		// infra endpoints so a connect-timeout cycle can be diagnosed from the
		// log alone (no need to read Railway's variable resolver UI).
		// Passwords are masked.
		printEnvProbe();

		SpringApplication.run(IrcApplication.class, args);
	}

	private static void printEnvProbe() {
		String[] keys = {
			"DB_URL", "DB_USERNAME", "DB_PASSWORD",
			"REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD",
			"RABBITMQ_HOST", "RABBITMQ_PORT", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
			"CASSANDRA_CONTACT_POINTS", "CASSANDRA_USERNAME", "CASSANDRA_PASSWORD",
			"ELASTICSEARCH_URIS"
		};
		System.out.println("─── ENV PROBE ────────────────────────────────────────────");
		for (String k : keys) {
			String v = System.getenv(k);
			boolean isSecret = k.endsWith("PASSWORD");
			String shown;
			if (v == null) {
				shown = "<UNSET>";
			} else if (v.isEmpty()) {
				shown = "<EMPTY>";
			} else if (isSecret) {
				shown = "<" + v.length() + " chars>";
			} else {
				shown = v;
			}
			System.out.println("  " + k + " = " + shown);
		}
		System.out.println("──────────────────────────────────────────────────────────");
	}

	@PostConstruct
	void enforceUtc() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}
}
