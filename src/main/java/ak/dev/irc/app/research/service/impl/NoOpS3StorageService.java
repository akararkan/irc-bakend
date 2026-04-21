package ak.dev.irc.app.research.service.impl;

import ak.dev.irc.app.common.exception.AppException;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * Fallback S3StorageService used when S3/R2 credentials are not configured.
 * All operations throw a Service Unavailable AppException so callers receive
 * a clear error instead of causing bean creation failures at startup.
 */
@Service
@Slf4j
@ConditionalOnExpression("'${app.storage.access-key:}' == '' or '${app.storage.secret-key:}' == ''")
public class NoOpS3StorageService implements S3StorageService {

    @Override
    public String upload(MultipartFile file, String prefix) {
        log.warn("Attempted to upload while storage is not configured: {}", file != null ? file.getOriginalFilename() : "<null>");
        throw new AppException("File storage service is not configured", HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
    }

    @Override
    public void delete(String s3Key) {
        log.warn("Attempted to delete '{}' while storage is not configured", s3Key);
        throw new AppException("File storage service is not configured", HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
    }

    @Override
    public String getPublicUrl(String s3Key) {
        log.warn("Attempted to getPublicUrl '{}' while storage is not configured", s3Key);
        throw new AppException("File storage service is not configured", HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
    }

    @Override
    public String getPreSignedUrl(String s3Key, int expiryMinutes) {
        log.warn("Attempted to getPreSignedUrl '{}' while storage is not configured", s3Key);
        throw new AppException("File storage service is not configured", HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
    }

    @Override
    public S3ObjectStream getObject(String s3Key) {
        log.warn("Attempted to getObject '{}' while storage is not configured", s3Key);
        throw new AppException("File storage service is not configured", HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
    }
}

