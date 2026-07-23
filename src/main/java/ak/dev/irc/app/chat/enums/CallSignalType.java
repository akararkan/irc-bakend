package ak.dev.irc.app.chat.enums;

/**
 * WebRTC signaling frame kind relayed between peers over SSE. The server is a
 * blind relay — it never inspects the SDP / ICE {@code payload}.
 */
public enum CallSignalType { OFFER, ANSWER, ICE }
