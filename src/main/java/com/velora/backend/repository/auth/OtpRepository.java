package com.velora.backend.repository.auth;

import com.velora.backend.entity.auth.Otp;
import com.velora.backend.entity.auth.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OtpRepository extends JpaRepository<Otp, Long> {

    Optional<Otp> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, OtpPurpose purpose);

    @Modifying
    @Query("delete from Otp o where o.email = :email and o.purpose = :purpose")
    int deleteByEmailAndPurpose(@Param("email") String email, @Param("purpose") OtpPurpose purpose);

    @Modifying
    @Query("delete from Otp o where o.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
