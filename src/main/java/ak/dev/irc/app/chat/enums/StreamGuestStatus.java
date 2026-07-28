package ak.dev.irc.app.chat.enums;

/**
 * A viewer's journey on and off a live stream's <b>stage</b> — the TikTok-style
 * multi-guest panel where invited viewers come "up" and talk beside the host.
 *
 * <ul>
 *   <li>{@code REQUESTED} — the viewer raised their hand (asked to come up). The
 *       host sees it and may approve or deny.</li>
 *   <li>{@code INVITED}   — the host invited this viewer up; they may accept or
 *       decline.</li>
 *   <li>{@code ACTIVE}    — on stage right now, publishing their own camera/mic.
 *       Only {@code ACTIVE} guests hold live publish credentials and count
 *       against the stage limit.</li>
 *   <li>{@code REMOVED}   — was on stage and is no longer (stepped down, taken
 *       down by the host, or the stream ended). Publish credentials are revoked.</li>
 *   <li>{@code DECLINED}  — a request the host denied, or an invite the viewer
 *       turned down. Terminal until a fresh request/invite reopens the row.</li>
 * </ul>
 */
public enum StreamGuestStatus { REQUESTED, INVITED, ACTIVE, REMOVED, DECLINED }
