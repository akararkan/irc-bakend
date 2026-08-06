package ak.dev.irc.app.research.service.impl;

import ak.dev.irc.app.common.exception.AppException;
import ak.dev.irc.app.common.messages.ResearchMessages;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
@Primary
// Only enable this Cloudflare R2 implementation when storage credentials are configured
@ConditionalOnExpression("'${app.storage.access-key:}' != '' and '${app.storage.secret-key:}' != ''")
@RequiredArgsConstructor
@Slf4j
public class CloudflareR2StorageService implements S3StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${app.storage.bucket-name}")
    private String bucketName;

    /**
     * Optional absolute base for media URLs returned by {@link #getPublicUrl}.
     * When set (e.g. {@code https://irc-bakend-production.up.railway.app}),
     * the proxy URL becomes absolute — required so a cross-origin frontend's
     * {@code <video src="...">} / {@code <img src="...">} elements resolve
     * to the backend instead of the frontend's own origin. Blank in dev so
     * same-origin (localhost) calls keep working with relative URLs.
     *
     * <p>Set via the {@code MEDIA_PUBLIC_URL} env var on the deployment
     * (Railway / k8s / etc.). Trailing slashes are stripped.</p>
     */
    @Value("${app.media.public-url-prefix:}")
    private String mediaPublicUrlPrefix;

    // ══════════════════════════════════════════════════════════════════════════
    //  UPLOAD
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public String upload(MultipartFile file, String prefix) {
        String extension = extractExtension(file.getOriginalFilename());
        String key = prefix + "/" + UUID.randomUUID() + extension;

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            log.info("Uploaded file to R2: {}", key);
            return key;

        } catch (SdkClientException e) {
            log.error("R2 storage is unreachable — upload failed for '{}': {}",
                    file.getOriginalFilename(), e.getMessage(), e);
            throw new AppException(
                    ResearchMessages.STORAGE_UNAVAILABLE_MSG,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ResearchMessages.STORAGE_UNAVAILABLE,
                    e,
                    Map.of("filename", file.getOriginalFilename() != null
                            ? file.getOriginalFilename() : "unknown"));
        } catch (IOException e) {
            log.error("Failed to read upload file '{}'", file.getOriginalFilename(), e);
            throw new AppException(ResearchMessages.FILE_UPLOAD_ERROR_MSG,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ResearchMessages.FILE_UPLOAD_ERROR,
                    e,
                    Map.of("filename", file.getOriginalFilename() != null
                            ? file.getOriginalFilename() : "unknown"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void delete(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) return;

        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build());
            log.info("Deleted file from R2: {}", s3Key);
        } catch (Exception e) {
            log.warn("Failed to delete file from R2: {}", s3Key, e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PUBLIC URL (proxy through backend)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public String getPublicUrl(String s3Key) {
        if (s3Key == null) return null;
        String path = "/api/v1/media/" + s3Key;
        if (mediaPublicUrlPrefix == null || mediaPublicUrlPrefix.isBlank()) {
            // No prefix configured (typical for dev): return the relative
            // path so same-origin (localhost) calls still work as before.
            return path;
        }
        // Absolute URL — needed by cross-origin frontends (the React app on
        // Vercel) so <video src> / <img src> resolves to the backend host,
        // not the frontend's own origin.
        String base = mediaPublicUrlPrefix;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GET OBJECT (stream from R2)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public S3ObjectStream getObject(String s3Key) {
        return getObject(s3Key, null);
    }

    @Override
    public S3ObjectStream getObject(String s3Key, String rangeHeader) {
        try {
            GetObjectRequest.Builder req = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key);
            // Forward the browser's Range header straight to R2/S3 — the store
            // returns the partial body + a Content-Range, so we stream only the
            // requested bytes instead of buffering the whole object.
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                req.range(rangeHeader);
            }

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(req.build());
            GetObjectResponse metadata = response.response();
            String contentRange = metadata.contentRange();   // null for a full GET
            return new S3ObjectStream(
                    response,
                    metadata.contentType(),
                    metadata.contentLength(),
                    contentRange,
                    parseTotalLength(contentRange, metadata.contentLength())
            );
        } catch (SdkClientException e) {
            log.error("R2 storage is unreachable — cannot retrieve object '{}': {}",
                    s3Key, e.getMessage(), e);
            throw new AppException(
                    ResearchMessages.STORAGE_UNAVAILABLE_MSG,
                    HttpStatus.SERVICE_UNAVAILABLE, ResearchMessages.STORAGE_UNAVAILABLE);
        } catch (Exception e) {
            log.error("Failed to get object from R2: {}", s3Key, e);
            throw new AppException(ResearchMessages.MEDIA_NOT_FOUND_MSG,
                    HttpStatus.NOT_FOUND, ResearchMessages.MEDIA_NOT_FOUND);
        }
    }

    /** Extract the full object size from a {@code "bytes start-end/total"} Content-Range. */
    private static Long parseTotalLength(String contentRange, long partialLength) {
        if (contentRange != null) {
            int slash = contentRange.indexOf('/');
            if (slash >= 0 && slash < contentRange.length() - 1) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) { /* "*" or malformed → fall through */ }
            }
        }
        return partialLength > 0 ? partialLength : null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRE-SIGNED URL
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public String getPreSignedUrl(String s3Key, int expiryMinutes) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expiryMinutes))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .build())
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRE-SIGNED PUT  (direct-to-R2 upload — spec §20.4)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public String presignPut(String s3Key, String contentType, int expiryMinutes) {
        PutObjectRequest.Builder put = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key);
        if (contentType != null && !contentType.isBlank()) {
            put.contentType(contentType);
        }
        var presign = software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expiryMinutes))
                .putObjectRequest(put.build())
                .build();
        return s3Presigner.presignPutObject(presign).url().toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PUT BYTES  (worker-produced renditions — spec §20.4)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public String putBytes(byte[] data, String s3Key, String contentType) {
        try {
            PutObjectRequest.Builder put = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentLength((long) (data == null ? 0 : data.length));
            if (contentType != null && !contentType.isBlank()) {
                put.contentType(contentType);
            }
            s3Client.putObject(put.build(), RequestBody.fromBytes(data == null ? new byte[0] : data));
            log.info("Uploaded {} bytes to R2: {}", data == null ? 0 : data.length, s3Key);
            return s3Key;
        } catch (SdkClientException e) {
            log.error("R2 storage is unreachable — putBytes failed for '{}': {}", s3Key, e.getMessage(), e);
            throw new AppException(
                    ResearchMessages.STORAGE_UNAVAILABLE_MSG,
                    HttpStatus.SERVICE_UNAVAILABLE, ResearchMessages.STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public java.util.List<StoredObject> list(String prefix, int maxKeys) {
        int cap = Math.max(1, Math.min(maxKeys, 10_000));
        java.util.List<StoredObject> out = new java.util.ArrayList<>();
        try {
            String continuation = null;
            do {
                var reqB = software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .maxKeys(Math.min(1000, cap - out.size()));
                if (prefix != null && !prefix.isBlank()) reqB.prefix(prefix);
                if (continuation != null) reqB.continuationToken(continuation);
                var resp = s3Client.listObjectsV2(reqB.build());
                for (var obj : resp.contents()) {
                    out.add(new StoredObject(obj.key(), obj.size(),
                            obj.lastModified() == null ? 0 : obj.lastModified().toEpochMilli()));
                    if (out.size() >= cap) return out;
                }
                continuation = Boolean.TRUE.equals(resp.isTruncated())
                        ? resp.nextContinuationToken() : null;
            } while (continuation != null);
            return out;
        } catch (SdkClientException e) {
            log.error("R2 storage is unreachable — list failed for prefix '{}': {}", prefix, e.getMessage());
            throw new AppException(
                    ResearchMessages.STORAGE_UNAVAILABLE_MSG,
                    HttpStatus.SERVICE_UNAVAILABLE, ResearchMessages.STORAGE_UNAVAILABLE);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════

    private String extractExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.'));
        }
        return "";
    }
}
