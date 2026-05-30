package ak.dev.irc;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
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
		probePostgres();
		bootstrapCassandraKeyspace();

		SpringApplication.run(IrcApplication.class, args);
	}

	/**
	 * Spring Boot's CassandraAutoConfiguration builds the CqlSession with
	 * `keyspace-name` baked in — so on a fresh cluster the very first connect
	 * fails with InvalidKeyspaceException because the keyspace doesn't exist
	 * yet (schema-action=CREATE_IF_NOT_EXISTS only creates tables, not the
	 * keyspace itself). We sidestep that by opening a short-lived session
	 * WITHOUT a keyspace, running CREATE KEYSPACE IF NOT EXISTS, and closing.
	 * Idempotent — silently does nothing on every subsequent boot.
	 */
	private static void bootstrapCassandraKeyspace() {
		System.out.println("─── CQL KEYSPACE BOOTSTRAP ───────────────────────────────");
		String contactPoints = System.getenv("CASSANDRA_CONTACT_POINTS");
		String dc            = System.getenv().getOrDefault("CASSANDRA_DATACENTER", "datacenter1");
		String keyspace      = System.getenv().getOrDefault("CASSANDRA_KEYSPACE",   "irc_keyspace");
		String user          = System.getenv().getOrDefault("CASSANDRA_USERNAME",   "cassandra");
		String pass          = System.getenv().getOrDefault("CASSANDRA_PASSWORD",   "cassandra");

		if (contactPoints == null || contactPoints.isEmpty()) {
			System.out.println("  CASSANDRA_CONTACT_POINTS unset — skipping");
			System.out.println("──────────────────────────────────────────────────────────");
			return;
		}
		// Refuse anything that isn't a plain CQL identifier — the keyspace name
		// is interpolated into the DDL string, so don't let env-var content turn
		// into a CQL injection vector even though it's our own value.
		if (!keyspace.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
			System.out.println("  ✗ invalid keyspace name: " + keyspace);
			System.out.println("──────────────────────────────────────────────────────────");
			return;
		}

		List<InetSocketAddress> addrs = new ArrayList<>();
		for (String hp : contactPoints.split(",")) {
			hp = hp.trim();
			if (hp.isEmpty()) continue;
			int colon = hp.lastIndexOf(':');
			String h = colon > 0 ? hp.substring(0, colon) : hp;
			int p    = colon > 0 ? Integer.parseInt(hp.substring(colon + 1)) : 9042;
			addrs.add(new InetSocketAddress(h, p));
		}
		System.out.println("  contact points = " + contactPoints);
		System.out.println("  datacenter     = " + dc);
		System.out.println("  keyspace       = " + keyspace);

		try (CqlSession session = CqlSession.builder()
				.addContactPoints(addrs)
				.withLocalDatacenter(dc)
				.withAuthCredentials(user, pass)
				.build()) {
			session.execute(
				"CREATE KEYSPACE IF NOT EXISTS " + keyspace +
				" WITH replication = {'class':'SimpleStrategy','replication_factor':1}"
			);
			System.out.println("  ✓ keyspace ready");
		} catch (Exception e) {
			System.out.println("  ✗ " + e.getClass().getSimpleName() + ": " + e.getMessage());
			System.out.println("  → Cassandra bootstrap failed; Spring will fail next with a clearer error.");
		}
		System.out.println("──────────────────────────────────────────────────────────");
	}

	private static void probePostgres() {
		System.out.println("─── PG NET PROBE ─────────────────────────────────────────");
		String url = System.getenv("DB_URL");
		if (url == null || url.isEmpty()) {
			System.out.println("  DB_URL unset — skipping");
			System.out.println("──────────────────────────────────────────────────────────");
			return;
		}

		// Accept either form so a wrong env var shape doesn't blow up the probe
		// itself (and mask the real fault). The app still needs the jdbc: form
		// for Hikari, but the probe is only checking network reachability.
		//   jdbc:postgresql://HOST:PORT/db?params
		//   postgresql://user:pass@HOST:PORT/db?params
		String host;
		int port;
		try {
			String s = url.replaceFirst("^jdbc:", "");          // drop jdbc: if present
			s = s.replaceFirst("^postgres(ql)?://", "");        // drop scheme
			int at = s.indexOf('@');                            // drop user:pass@ if present
			if (at >= 0) s = s.substring(at + 1);
			int slash = s.indexOf('/');
			String hostport = slash > 0 ? s.substring(0, slash) : s;
			int qmark = hostport.indexOf('?');
			if (qmark >= 0) hostport = hostport.substring(0, qmark);
			int colon = hostport.lastIndexOf(':');
			if (colon > 0 && colon < hostport.length() - 1) {
				host = hostport.substring(0, colon);
				port = Integer.parseInt(hostport.substring(colon + 1));
			} else {
				host = hostport;
				port = 5432;
			}
		} catch (Exception e) {
			System.out.println("  could not parse DB_URL: " + e.getClass().getSimpleName()
				+ ": " + e.getMessage());
			System.out.println("  raw DB_URL = " + url.replaceAll(":[^:@/]+@", ":***@"));
			System.out.println("  → DB_URL must be jdbc:postgresql://HOST:PORT/DB form for Hikari.");
			System.out.println("──────────────────────────────────────────────────────────");
			return;
		}
		System.out.println("  host = " + host);
		System.out.println("  port = " + port);

		// 1. DNS resolution
		try {
			InetAddress[] addrs = InetAddress.getAllByName(host);
			System.out.println("  DNS  ✓ resolved " + addrs.length + " address(es):");
			for (InetAddress a : addrs) {
				System.out.println("        " + a.getHostAddress()
					+ " (" + (a.getAddress().length == 4 ? "IPv4" : "IPv6") + ")");
			}
		} catch (Exception e) {
			System.out.println("  DNS  ✗ " + e.getClass().getSimpleName() + ": " + e.getMessage());
			System.out.println("  → hostname does not resolve from this container.");
			System.out.println("    Postgres service is in a different project, was deleted,");
			System.out.println("    or the variable reference is wrong.");
			System.out.println("──────────────────────────────────────────────────────────");
			return;
		}

		// 2. TCP connect with a short timeout
		long t0 = System.currentTimeMillis();
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), 5000);
			long ms = System.currentTimeMillis() - t0;
			System.out.println("  TCP  ✓ connected in " + ms + " ms");
			System.out.println("  → Network path is fine. If Hikari still fails, it's auth/SSL.");
		} catch (java.net.SocketTimeoutException e) {
			long ms = System.currentTimeMillis() - t0;
			System.out.println("  TCP  ✗ timed out after " + ms + " ms");
			System.out.println("  → IP resolved, but nothing is listening on port " + port + ".");
			System.out.println("    Either Postgres service isn't running, or it's");
			System.out.println("    unreachable from this service's network (different project /");
			System.out.println("    region / IPv4-vs-IPv6 mismatch).");
		} catch (Exception e) {
			System.out.println("  TCP  ✗ " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		System.out.println("──────────────────────────────────────────────────────────");
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
