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
		SpringApplication.run(IrcApplication.class, args);
	}

	@PostConstruct
	void enforceUtc() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}
}
