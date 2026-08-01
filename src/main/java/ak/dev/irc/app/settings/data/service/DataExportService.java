package ak.dev.irc.app.settings.data.service;

import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.settings.data.entity.ExportJob;
import ak.dev.irc.app.settings.data.enums.ExportStatus;
import ak.dev.irc.app.settings.data.repository.ExportJobRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Personal-data export (spec §16). Rate-limited to 1 per 30 days per user. The
 * export runs off the request thread on a small single-thread pool and streams a
 * ZIP to a temp file.
 *
 * <p>SEAM: production should stream the archive to R2 ({@code S3StorageService})
 * and hand back a presigned 48h URL rather than serving from local temp storage;
 * the worker here writes to {@code java.io.tmpdir} to stay self-contained.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final ExportJobRepository jobRepo;
    private final UserRepository userRepo;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "data-export-worker");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    @Transactional
    public ExportJob requestExport(UUID userId) {
        // Throws RateLimitExceededException (→ 429) when exceeded.
        rateLimiter.check("privacy:export", userId, 1, Duration.ofDays(30));
        ExportJob job = jobRepo.save(ExportJob.builder()
                .userId(userId)
                .status(ExportStatus.PENDING)
                .build());
        UUID jobId = job.getId();
        worker.submit(() -> runExport(jobId, userId));
        return job;
    }

    @Transactional(readOnly = true)
    public ExportJob getJob(UUID userId, UUID jobId) {
        ExportJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("ExportJob", "id", jobId));
        if (!job.getUserId().equals(userId)) {
            throw new ForbiddenException("Not your export job.", "NOT_EXPORT_OWNER");
        }
        // Reflect expiry lazily on read.
        if (job.getStatus() == ExportStatus.READY && job.getExpiresAt() != null
                && job.getExpiresAt().isBefore(LocalDateTime.now())) {
            job.setStatus(ExportStatus.EXPIRED);
            jobRepo.save(job);
        }
        return job;
    }

    /** Returns an open stream + length for the controller to serve. */
    @Transactional(readOnly = true)
    public Download openDownload(UUID userId, UUID jobId) {
        ExportJob job = getJob(userId, jobId);
        if (job.getStatus() != ExportStatus.READY || job.getFilePath() == null) {
            throw new ResourceNotFoundException("Export not ready or expired.");
        }
        try {
            Path p = Path.of(job.getFilePath());
            return new Download(Files.newInputStream(p), job.getSizeBytes() == null ? -1 : job.getSizeBytes());
        } catch (Exception ex) {
            throw new ResourceNotFoundException("Export file is no longer available.");
        }
    }

    public record Download(InputStream stream, long length) {}

    // ── worker ──────────────────────────────────────────────────────────────────

    private void runExport(UUID jobId, UUID userId) {
        markRunning(jobId);
        try {
            User user = userRepo.findActiveWithProfileByIdIn(List.of(userId))
                    .stream().findFirst().orElse(null);
            if (user == null) {
                fail(jobId, "Account not found or deactivated.");
                return;
            }
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(buildExport(user));

            Path zip = Files.createTempFile("irc-export-" + jobId, ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
                zos.putNextEntry(new ZipEntry("export.json"));
                zos.write(json);
                zos.closeEntry();
            }
            long size = Files.size(zip);
            complete(jobId, zip.toString(), size);
            log.info("[DATA-EXPORT] job {} ready for user {} ({} bytes)", jobId, userId, size);
        } catch (Exception ex) {
            log.error("[DATA-EXPORT] job {} failed: {}", jobId, ex.getMessage(), ex);
            fail(jobId, ex.getMessage());
        }
    }

    private Map<String, Object> buildExport(User user) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId());
        account.put("username", user.getUsername());
        account.put("email", user.getEmail());
        account.put("firstName", user.getFname());
        account.put("lastName", user.getLname());
        account.put("role", user.getRole() != null ? user.getRole().name() : null);
        account.put("createdAt", user.getCreatedAt());
        account.put("emailVerifiedAt", user.getEmailVerifiedAt());

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("avatarUrl", user.getProfileImage());
        profile.put("bio", user.getProfileBio());
        profile.put("location", user.getLocation());
        profile.put("orcidId", user.getOrcidId());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exportedAt", LocalDateTime.now());
        root.put("account", account);
        root.put("profile", profile);
        // SEAM: extend with posts/comments/reactions (JSONL), Cassandra messages,
        // and media references per §16 when the export scope is widened.
        return root;
    }

    @Transactional
    protected void markRunning(UUID jobId) {
        jobRepo.findById(jobId).ifPresent(j -> {
            j.setStatus(ExportStatus.RUNNING);
            jobRepo.save(j);
        });
    }

    @Transactional
    protected void complete(UUID jobId, String path, long size) {
        jobRepo.findById(jobId).ifPresent(j -> {
            j.setStatus(ExportStatus.READY);
            j.setFilePath(path);
            j.setSizeBytes(size);
            j.setReadyAt(LocalDateTime.now());
            j.setExpiresAt(LocalDateTime.now().plusHours(48));
            jobRepo.save(j);
        });
    }

    @Transactional
    protected void fail(UUID jobId, String message) {
        jobRepo.findById(jobId).ifPresent(j -> {
            j.setStatus(ExportStatus.FAILED);
            j.setErrorMessage(message != null && message.length() > 500
                    ? message.substring(0, 500) : message);
            jobRepo.save(j);
        });
    }
}
