package ak.dev.irc.app.security.otp.repository;

import ak.dev.irc.app.security.otp.entity.OtpChallenge;
import ak.dev.irc.app.security.otp.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    Optional<OtpChallenge> findFirstByDestinationHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String destinationHash, OtpPurpose purpose);
}
