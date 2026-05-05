package ak.dev.irc.app.audit.enums;

/**
 * Coarse operation type derived from the HTTP verb (or set explicitly by
 * service-layer audit calls). Keeps the audit log filterable without locking
 * us into the verb spelling — admins can ask for "all UPDATEs" cleanly.
 */
public enum AuditOperation {
    READ,        // GET, HEAD
    CREATE,      // POST (default)
    UPDATE,      // PUT, PATCH
    DELETE,      // DELETE
    LOGIN,       // POST /auth/login
    LOGOUT,      // POST /auth/logout
    UPLOAD,      // multipart POST
    SYSTEM,      // internal action, not HTTP-bound
    OTHER
}
