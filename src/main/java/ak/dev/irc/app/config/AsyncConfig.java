package ak.dev.irc.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Default executor for all bare {@code @Async} methods (fanout, search indexing,
 * notification dispatch, activity writes). Named executors (auditExecutor,
 * emailExecutor) are unaffected — they are referenced by name at the call site.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(
            @Value("${irc.async.pool.core-size:8}") int coreSize,
            @Value("${irc.async.pool.max-size:32}") int maxSize,
            @Value("${irc.async.pool.queue-capacity:10000}") int queueCapacity) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(coreSize);
        exec.setMaxPoolSize(maxSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setThreadNamePrefix("async-");
        exec.setKeepAliveSeconds(60);
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
