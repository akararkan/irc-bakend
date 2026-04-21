package ak.dev.irc.app.research.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * Abstraction over Cloudflare R2 (S3-compatible) storage.
 * Handles upload, delete, and pre-signed URL generation for research media.
 */
public interface S3StorageService {

    /**
     * Uploads a file to the given S3 prefix directory.
     *
     * @param file       the multipart file to upload
     * @param prefix     e.g. "research/{researchId}/media" or "research/{researchId}/promo"
     * @return the S3 object key (relative path within the bucket)
     */
    String upload(MultipartFile file, String prefix);

    /**
     * Deletes an object from the bucket.
     *
     * @param s3Key the object key returned from {@link #upload}
     */
    void delete(String s3Key);

    /**
     * Generates a proxy-based URL for the given key (served through the backend).
     *
     * @param s3Key the object key
     * @return the proxy URL path (e.g. /api/v1/media/posts/media/xxx.jpg)
     */
    String getPublicUrl(String s3Key);

    /**
     * Generates a time-limited pre-signed URL for private downloads.
     *
     * @param s3Key          the object key
     * @param expiryMinutes  minutes until the URL expires
     * @return the pre-signed URL
     */
    String getPreSignedUrl(String s3Key, int expiryMinutes);

    /**
     * Retrieves an object from the bucket as a stream (used for media proxying).
     *
     * @param s3Key the object key
     * @return the response input stream wrapper containing both the stream and metadata
     */
    S3ObjectStream getObject(String s3Key);

    /**
     * Wrapper holding a streamed S3 object and its metadata.
     */
    record S3ObjectStream(InputStream inputStream, String contentType, long contentLength) {}
}
