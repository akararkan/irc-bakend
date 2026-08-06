package ak.dev.irc.app.admin.ops;

import ak.dev.irc.app.chat.service.MediaControlClient;
import ak.dev.irc.app.email.EmailService;
import ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants;
import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The composite dependency probe behind {@code GET /api/v1/admin/ops/health}
 * (operations.md §3.2) — one bounded check per dependency, each returning
 * status + latency, none allowed to take the endpoint down with it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsHealthService {

    private final DataSource dataSource;
    private final CqlSession cqlSession;
    private final StringRedisTemplate redis;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;
    private final ElasticsearchOperations esOps;
    private final ObjectProvider<S3Client> s3Client;
    private final MediaControlClient mediaControlClient;
    private final EmailService emailService;

    @Value("${app.storage.bucket-name:}")
    private String r2Bucket;

    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("postgres", probe(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("SELECT 1");
            }
        }));
        out.put("cassandra", probe(() ->
                cqlSession.execute("SELECT release_version FROM system.local").one()));
        out.put("redis", probe(() -> {
            String pong = redis.execute(connection -> connection.ping(), true);
            if (pong == null) throw new IllegalStateException("no PONG");
        }));
        out.put("rabbitmq", probeRabbit());
        out.put("elasticsearch", probe(() -> esOps.indexOps(
                ak.dev.irc.app.user.search.document.UserSearchDocument.class).exists()));
        out.put("r2", probeR2());
        out.put("mediamtx", probe(() -> {
            if (!mediaControlClient.ping()) throw new IllegalStateException("control API unreachable");
        }));
        out.put("mail", Map.of("status", emailService.isEnabled() ? "UP" : "DISABLED"));
        return out;
    }

    private Map<String, Object> probeRabbit() {
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        if (admin == null) {
            return Map.of("status", "UNKNOWN", "note", "RabbitAdmin unavailable");
        }
        long start = System.currentTimeMillis();
        Map<String, Object> queues = new LinkedHashMap<>();
        boolean up = true;
        for (String queue : List.of(RabbitMQConstants.NOTIFICATION_QUEUE,
                RabbitMQConstants.ANALYTICS_QUEUE, RabbitMQConstants.DEAD_LETTER_QUEUE,
                RabbitMQConstants.MEDIA_PROCESS_QUEUE, RabbitMQConstants.MEDIA_DELETE_QUEUE)) {
            try {
                var props = admin.getQueueProperties(queue);
                queues.put(queue, props == null ? "missing"
                        : props.get("QUEUE_MESSAGE_COUNT"));
                if (props == null) up = false;
            } catch (Exception e) {
                queues.put(queue, "error: " + e.getMessage());
                up = false;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", up ? "UP" : "DEGRADED");
        result.put("latencyMs", System.currentTimeMillis() - start);
        result.put("queues", queues);
        return result;
    }

    private Map<String, Object> probeR2() {
        S3Client client = s3Client.getIfAvailable();
        if (client == null || r2Bucket == null || r2Bucket.isBlank()) {
            return Map.of("status", "DISABLED", "note", "R2 credentials not configured");
        }
        return probe(() -> client.headBucket(HeadBucketRequest.builder().bucket(r2Bucket).build()));
    }

    private interface Check {
        void run() throws Exception;
    }

    private static Map<String, Object> probe(Check check) {
        long start = System.currentTimeMillis();
        try {
            check.run();
            return Map.of("status", "UP", "latencyMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return Map.of("status", "DOWN",
                    "latencyMs", System.currentTimeMillis() - start,
                    "error", String.valueOf(e.getMessage()));
        }
    }
}
