package ak.dev.irc.app.chat.enums;

/**
 * Lifecycle of a call session.
 * <ul>
 *   <li>{@code RINGING} — created; invitees are being alerted; not yet answered.</li>
 *   <li>{@code ONGOING} — at least one invitee answered.</li>
 *   <li>{@code ENDED} — everyone hung up / left after the call connected.</li>
 *   <li>{@code DECLINED} — a 1:1 call the callee rejected.</li>
 *   <li>{@code MISSED} — rang out with no answer (marked by the ring-timeout sweep).</li>
 *   <li>{@code CANCELLED} — the initiator hung up before anyone answered.</li>
 * </ul>
 */
public enum CallStatus { RINGING, ONGOING, ENDED, DECLINED, MISSED, CANCELLED }
