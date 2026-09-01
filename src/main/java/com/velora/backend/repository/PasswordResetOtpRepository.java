package com.velora.backend.repository;

import com.velora.backend.entity.PasswordResetOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {

    Optional<PasswordResetOtp> findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(Long userId);

    /**
     * Requesting a new code invalidates any earlier outstanding one, so a user who taps
     * "forgot password" twice can't leave a second working code live.
     */
    @Modifying
    @Query("update PasswordResetOtp o set o.usedAt = :now where o.user.id = :userId and o.usedAt is null")
    int invalidateOutstandingForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
