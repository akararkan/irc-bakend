package ak.dev.irc.app.media.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * In-memory {@link MultipartFile} over a byte array. Lets the media worker reuse
 * the existing {@code S3StorageService.upload(MultipartFile, prefix)} to store
 * worker-produced rendition bytes without adding a putBytes method to the shared
 * storage interface. Pure JDK; no test-scope dependency.
 */
public class BytesMultipartFile implements MultipartFile {

    private final byte[] content;
    private final String name;
    private final String originalFilename;
    private final String contentType;

    public BytesMultipartFile(byte[] content, String originalFilename, String contentType) {
        this.content = content != null ? content : new byte[0];
        this.name = "file";
        this.originalFilename = originalFilename;
        this.contentType = contentType;
    }

    @Override public String getName() { return name; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override public byte[] getBytes() { return content; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        Files.write(dest.toPath(), content);
    }
}
