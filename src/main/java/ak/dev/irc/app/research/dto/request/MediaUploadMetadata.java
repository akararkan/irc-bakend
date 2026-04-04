package ak.dev.irc.app.research.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Per-file metadata sent alongside each uploaded file in the multipart
 * research create request.
 *
 * <p>The list of {@code MediaUploadMetadata} objects inside
 * {@link CreateResearchRequest#mediaFiles()} is matched to the
 * {@code files[]} multipart parts <b>by index</b>:
 * {@code files[0]} → {@code mediaFiles[0]}, and so on.
 *
 * <p>If a file has no corresponding metadata entry the file is still
 * uploaded with default values (null caption/altText, sequential
 * displayOrder).
 */
public record MediaUploadMetadata(

        @Size(max = 500, message = "Caption must not exceed 500 characters")
        String caption,

        @Size(max = 300, message = "Alt text must not exceed 300 characters")
        String altText,

        /**
         * 0-based display order within the research media gallery.
         * If omitted the server uses the file's position in the upload list.
         */
        Integer displayOrder
) {}