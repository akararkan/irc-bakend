package ak.dev.irc.app.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Payload the media server (MediaMTX) POSTs to the media-auth hook for every
 * publish/read attempt. We only act on a few fields; the rest are accepted and
 * ignored so the contract survives the media server adding fields.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaAuthRequest {
    private String user;
    private String password;   // for a publish: the stream's secret streamKey
    private String token;
    private String ip;
    private String action;     // publish | read | playback | api | metrics | pprof
    private String path;       // the stream id
    private String protocol;   // rtmp | hls | ...
    private String id;
    private String query;       // raw media-URL query string, e.g. "user=publisher&pass=<key>"
    private String userAgent;
}
