package com.skillsphere.repository;

import com.skillsphere.entity.PasswordResetOtp;
import com.skillsphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {

    @Query("SELECT o FROM PasswordResetOtp o JOIN FETCH o.user WHERE o.user = :user AND o.purpose = :purpose ORDER BY o.createdAt DESC LIMIT 1")
    Optional<PasswordResetOtp> findLatestByUserAndPurpose(@Param("user") User user, @Param("purpose") String purpose);

    @Query("SELECT o FROM PasswordResetOtp o JOIN FETCH o.user WHERE o.resetTokenHash = :tokenHash")
    Optional<PasswordResetOtp> findByResetTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("UPDATE PasswordResetOtp o SET o.consumedAt = CURRENT_TIMESTAMP WHERE o.id = :id AND o.consumedAt IS NULL AND o.resetTokenExpiresAt > CURRENT_TIMESTAMP AND o.verifiedAt IS NOT NULL")
    int markConsumedIfValid(@Param("id") Long id);

    @Modifying
    @Query("UPDATE PasswordResetOtp o SET o.verifiedAt = CURRENT_TIMESTAMP, o.resetTokenHash = :tokenHash, o.resetTokenExpiresAt = :expiresAt WHERE o.id = :id AND o.verifiedAt IS NULL AND o.consumedAt IS NULL AND o.expiresAt > CURRENT_TIMESTAMP")
    int markVerifiedIfEligible(@Param("id") Long id, @Param("tokenHash") String tokenHash, @Param("expiresAt") java.time.Instant expiresAt);

    @Modifying
    @Query("DELETE FROM PasswordResetOtp o WHERE o.user = :user AND (o.consumedAt IS NOT NULL OR o.expiresAt < CURRENT_TIMESTAMP)")
    void deleteExpiredOrConsumedByUser(@Param("user") User user);

    @Modifying
    @Query("UPDATE PasswordResetOtp o SET o.expiresAt = CURRENT_TIMESTAMP WHERE o.user = :user AND o.purpose = :purpose AND o.consumedAt IS NULL")
    void invalidateActiveOtpsByUserAndPurpose(@Param("user") User user, @Param("purpose") String purpose);

    @Modifying
    @Query("UPDATE PasswordResetOtp o SET o.consumedAt = CURRENT_TIMESTAMP WHERE o.user = :user")
    void invalidateAllForUser(@Param("user") User user);
}
