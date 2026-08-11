package ak.dev.irc.app.security.phone;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.SecurityMessages;
import ak.dev.irc.app.security.crypto.Hashing;
import ak.dev.irc.app.security.otp.enums.OtpPurpose;
import ak.dev.irc.app.security.otp.service.OtpService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Phone binding for a logged-in user (spec §4 "verify additional contact info").
 * Issues an OTP to the candidate number and, on verification, stores the E.164
 * plus its keyed HMAC (spec §3 — a keyed hash so a DB dump can't reverse the
 * number) and marks it verified.
 */
@Service
@RequiredArgsConstructor
public class PhoneService {

    private final OtpService otpService;
    private final UserRepository userRepo;
    private final PhoneNormalizer phoneNormalizer;
    private final ak.dev.irc.app.user.service.ContactMatchService contactMatchService;

    /** Server pepper for the phone HMAC — lives only in the environment. */
    @Value("${app.security.contact.pepper:${app.otp.pepper:}}")
    private String contactPepper;

    /** Send a verification code to the candidate phone. */
    @Transactional
    public void requestVerification(UUID userId, String rawPhone, String ip) {
        otpService.requestOtp(rawPhone, OtpPurpose.PHONE_VERIFY, ip, null);
    }

    /** Verify the code and bind the phone to the user. Returns the E.164. */
    @Transactional
    public String confirm(UUID userId, String rawPhone, String code) {
        String e164 = otpService.verifyOtp(rawPhone, code, OtpPurpose.PHONE_VERIFY);
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // One number, one account. Without this a number could be bound to
        // several accounts and a single contact-match hash would then resolve to
        // all of them — every synced address book would surface strangers.
        userRepo.findActiveByPhoneE164(e164)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> {
                    throw new ak.dev.irc.app.common.exception.ConflictException(
                            SecurityMessages.PHONE_ALREADY_BOUND_MSG, SecurityMessages.PHONE_ALREADY_BOUND);
                });

        user.setPhoneE164(e164);
        user.setPhoneHmac(Hashing.hmacSha256Hex(e164, contactPepper));
        user.setPhoneVerifiedAt(LocalDateTime.now());
        userRepo.save(user);

        // The number is only findable in other people's address books once it is
        // verified — this is the write that actually turns phone contact-sync on
        // for this account.
        contactMatchService.syncIdentityHashes(userId);
        return e164;
    }
}
