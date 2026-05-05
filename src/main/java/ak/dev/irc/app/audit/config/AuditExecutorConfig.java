package ak.dev.irc.app.audit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Audit writes happen on every request, so they need their own thread pool —
 * a queue saturating elsewhere (email, notifications) must not stall the
 * request loop, and audit failures must not stall those queues either.
 */
@Configuration
public class AuditExecutorConfig {

    @Bean(name = "auditExecutor")
    public Executor auditExecutor(
            @Value("${irc.audit.pool.core-size:4}") int coreSize,
            @Value("${irc.audit.pool.max-size:16}") int maxSize,
            @Value("${irc.audit.pool.queue-capacity:50000}") int queueCapacity) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(coreSize);
        exec.setMaxPoolSize(maxSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setThreadNamePrefix("audit-");
        exec.setKeepAliveSeconds(60);
        // CallerRunsPolicy: under extreme back-pressure the calling request
        // pays a tiny latency hit rather than dropping audit data.
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
