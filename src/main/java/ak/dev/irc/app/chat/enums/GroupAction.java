package ak.dev.irc.app.chat.enums;

/**
 * Every authority-gated action inside a GROUP. A single pure function
 * {@code can(actorRole, action, targetRole, settings)} resolves all of them so
 * role logic is never scattered across controllers.
 */
public enum GroupAction {
    SEND_MESSAGE,
    ADD_MEMBERS,
    REMOVE_MEMBER,
    PROMOTE_ADMIN,
    DEMOTE_ADMIN,
    RESTRICT_MEMBER,
    EDIT_INFO,          // name / avatar
    CHANGE_SETTINGS,
    PIN_MESSAGE,
    DELETE_ANY_MESSAGE,
    CREATE_INVITE,
    TRANSFER_OWNERSHIP,
    DELETE_GROUP
}
