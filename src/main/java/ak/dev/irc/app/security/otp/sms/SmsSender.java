package ak.dev.irc.app.security.otp.sms;

/**
 * SMS delivery abstraction (spec §2.4). A local Iraqi gateway and an
 * international fallback can be swapped without touching the auth service.
 * Delivery is fire-and-forget from the caller's perspective; a real impl should
 * push failures to a retry queue.
 */
public interface SmsSender {

    /**
     * Send an SMS.
     *
     * @param e164Phone destination in E.164
     * @param message   body
     * @return true if the send was accepted by the gateway
     */
    boolean send(String e164Phone, String message);
}
