package ak.dev.irc.app.media.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Adapts an assembled chunked-upload file on local disk to the
 * {@link MultipartFile} contract so it can ride the exact same
 * {@code MediaIngestService.ingest} path as a normal multipart request —
 * same policy, magic-byte gate, quota, pipeline and accounting.
 * {@link #getInputStream()} is re-readable (a fresh stream per call), which
 * the ingest path relies on for header sniffing + hashing.
 */
public final class TempFileMultipartFile implements MultipartFile {

    private final Path path;
    private final String originalFilename;
    private final String contentType;

    public TempFileMultipartFile(Path path, String originalFilename, String contentType) {
        this.path = path;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
    }

    @Override public String getName()             { return "file"; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType()      { return contentType; }

    @Override public boolean isEmpty() {
        return getSize() == 0;
    }

    @Override public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override public byte[] getBytes() throws IOException {
        return Files.readAllBytes(path);
    }

    @Override public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override public void transferTo(File dest) throws IOException {
        Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void transferTo(Path dest) throws IOException {
        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
    }
}
