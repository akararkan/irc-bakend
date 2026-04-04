package ak.dev.irc.app.research.service;

import org.springframework.web.multipart.MultipartFile;

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
     * Generates a publicly accessible CDN / pre-signed URL for the given key.
     *
     * @param s3Key the object key
     * @return the full URL
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
}
