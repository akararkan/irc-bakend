package ak.dev.irc.app.research.service.impl;

import ak.dev.irc.app.common.exception.AppException;
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
                    "File storage service is currently unavailable. Please try again later.",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "STORAGE_UNAVAILABLE",
                    e,
                    Map.of("filename", file.getOriginalFilename() != null
                            ? file.getOriginalFilename() : "unknown"));
        } catch (IOException e) {
            log.error("Failed to read upload file '{}'", file.getOriginalFilename(), e);
            throw new AppException("File upload failed",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FILE_UPLOAD_ERROR",
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
        return "/api/v1/media/" + s3Key;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GET OBJECT (stream from R2)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public S3ObjectStream getObject(String s3Key) {
        try {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Key)
                            .build());
            GetObjectResponse metadata = response.response();
            return new S3ObjectStream(
                    response,
                    metadata.contentType(),
                    metadata.contentLength()
            );
        } catch (SdkClientException e) {
            log.error("R2 storage is unreachable — cannot retrieve object '{}': {}",
                    s3Key, e.getMessage(), e);
            throw new AppException(
                    "File storage service is currently unavailable. Please try again later.",
                    HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        } catch (Exception e) {
            log.error("Failed to get object from R2: {}", s3Key, e);
            throw new AppException("Failed to retrieve media file",
                    HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
        }
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

    private String extractExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.'));
        }
        return "";
    }
}
