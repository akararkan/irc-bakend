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
     * Pre-signed <b>PUT</b> URL for direct-to-storage uploads (spec §20.4) — a
     * 512 MB video must never stream through the app server. The client PUTs the
     * bytes straight to R2/S3 using this URL.
     *
     * <p>Default throws {@link UnsupportedOperationException}; the real R2 impl
     * overrides it. Callers behind the No-op (storage unconfigured) will surface
     * the error, which is the intended dev behaviour.</p>
     *
     * @param s3Key         the object key the client will PUT to
     * @param contentType   the declared content type (bound into the signature)
     * @param expiryMinutes minutes until the URL expires
     */
    default String presignPut(String s3Key, String contentType, int expiryMinutes) {
        throw new UnsupportedOperationException("presignPut is not supported by this storage backend");
    }

    /**
     * Upload raw bytes at a specific key (spec §20.4) — used by the media worker
     * to write re-encoded renditions it produced in memory. Returns the key.
     *
     * <p>Default throws {@link UnsupportedOperationException}; the real R2 impl
     * overrides it.</p>
     */
    default String putBytes(byte[] data, String s3Key, String contentType) {
        throw new UnsupportedOperationException("putBytes is not supported by this storage backend");
    }

    /**
     * Ranged fetch for HTTP {@code 206 Partial Content} (video/audio seeking).
     *
     * @param s3Key       the object key
     * @param rangeHeader the raw HTTP {@code Range} value (e.g. {@code "bytes=0-1023"});
     *                    {@code null}/blank → full object
     * @return the stream wrapper; {@link S3ObjectStream#contentRange()} is non-null
     *         when the store returned a partial body
     *
     * <p>Default impl ignores the range and returns the whole object (so storage
     * backends that can't range still work); the R2/S3 impl forwards it to the
     * store for a true partial read.</p>
     */
    default S3ObjectStream getObject(String s3Key, String rangeHeader) {
        return getObject(s3Key);
    }

    /**
     * Lists object keys under a prefix (bucket reconciliation — admin
     * media-storage.md §7). Returns up to {@code maxKeys} entries; the store
     * is paged internally.
     *
     * <p>Default throws {@link UnsupportedOperationException}; the real R2
     * impl overrides it. The No-op backend surfaces the clear
     * storage-unconfigured error instead.</p>
     */
    default java.util.List<StoredObject> list(String prefix, int maxKeys) {
        throw new UnsupportedOperationException("list is not supported by this storage backend");
    }

    /** One listed bucket object: key, size and last-modified epoch millis. */
    record StoredObject(String key, long sizeBytes, long lastModifiedEpochMs) {}

    /**
     * Wrapper holding a streamed S3 object and its metadata.
     *
     * @param contentRange the {@code Content-Range} header value when this is a
     *                     partial (206) response (e.g. {@code "bytes 0-1023/713494"});
     *                     {@code null} for a full (200) response
     * @param totalLength  the full object size in bytes (even for a partial read);
     *                     {@code null} if unknown
     */
    record S3ObjectStream(InputStream inputStream, String contentType, long contentLength,
                          String contentRange, Long totalLength) {

        /** Full-object convenience: no partial-content metadata. */
        public S3ObjectStream(InputStream inputStream, String contentType, long contentLength) {
            this(inputStream, contentType, contentLength, null, null);
        }
    }
}
