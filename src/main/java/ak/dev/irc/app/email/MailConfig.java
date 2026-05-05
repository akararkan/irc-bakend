package ak.dev.irc.app.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated executor for outbound email so a slow / unavailable SMTP server
 * never starves the request thread pool or the async dispatcher pool.
 *
 * <p>The pool is intentionally small but with a fat queue — email is best
 * served by a small handful of concurrent SMTP sessions, while transient
 * spikes (a viral post that triggers many notifications in a few seconds)
 * absorb in the queue without dropping work.</p>
 */
@Configuration
public class MailConfig {

    @Bean(name = "emailExecutor")
    public Executor emailExecutor(
            @Value("${irc.email.pool.core-size:4}") int coreSize,
            @Value("${irc.email.pool.max-size:8}") int maxSize,
            @Value("${irc.email.pool.queue-capacity:5000}") int queueCapacity) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(coreSize);
        exec.setMaxPoolSize(maxSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setThreadNamePrefix("email-");
        exec.setKeepAliveSeconds(60);
        // If the queue fills up, run on the caller thread rather than dropping
        // — losing a notification email is worse than slowing the producer.
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
