package ak.dev.irc.app.research.service.impl;

import ak.dev.irc.app.common.exception.AppException;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudflareR2StorageService implements S3StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${app.storage.bucket-name}")
    private String bucketName;

    @Value("${app.storage.public-url-base}")
    private String publicUrlBase;

    // ── Upload ───────────────────────────────────────────────────────────────

    @Override
    public String upload(MultipartFile file, String prefix) {
        try {
            String originalName = file.getOriginalFilename();
            String extension = "";
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf('.'));
            }

            String s3Key = prefix + "/" + UUID.randomUUID() + extension;

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            log.info("Uploaded file to R2: {}", s3Key);
            return s3Key;

        } catch (IOException e) {
            log.error("Failed to upload file to R2", e);
            throw new AppException("File upload failed",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FILE_UPLOAD_ERROR",
                    e,
                    Map.of("filename", file.getOriginalFilename()));
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

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

    // ── Public URL ───────────────────────────────────────────────────────────

    @Override
    public String getPublicUrl(String s3Key) {
        if (s3Key == null) return null;
        // Cloudflare R2 public bucket or custom domain
        return publicUrlBase.endsWith("/")
                ? publicUrlBase + s3Key
                : publicUrlBase + "/" + s3Key;
    }

    // ── Pre-signed URL ───────────────────────────────────────────────────────

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
}
