package ak.dev.irc.app.audit.enums;

public enum AuditOutcome {
    /** 2xx — request succeeded. */
    SUCCESS,
    /** 3xx — request redirected. */
    REDIRECT,
    /** 4xx — client error / forbidden / not found. */
    CLIENT_ERROR,
    /** 5xx — server error / unhandled exception. */
    SERVER_ERROR,
    /** Audit recorded for an action that wasn't an HTTP request (scheduled job, internal). */
    SYSTEM
}
