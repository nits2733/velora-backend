package com.velora.backend.repository.auth;

import com.velora.backend.entity.auth.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Requesting a new reset invalidates any earlier outstanding one, so a user who
     * clicks "forgot password" twice can't leave a second working link alive.
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.user.id = :userId and t.usedAt is null")
    int invalidateOutstandingForUser(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :cutoff or t.usedAt is not null")
    int deleteByExpiresAtBeforeOrUsedAtIsNotNull(@Param("cutoff") Instant cutoff);
}
