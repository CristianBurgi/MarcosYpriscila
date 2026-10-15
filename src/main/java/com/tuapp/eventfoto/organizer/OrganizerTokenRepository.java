package com.tuapp.eventfoto.organizer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizerTokenRepository extends JpaRepository<OrganizerToken, UUID> {

    Optional<OrganizerToken> findByTokenHash(String tokenHash);

    List<OrganizerToken> findByOrganizerIdAndPurposeAndUsedAtIsNull(UUID organizerId, OrganizerTokenPurpose purpose);

    /** Consume el token: 1 si lo gastó esta llamada, 0 si otro pedido ya lo había usado o venció en el medio. */
    @Modifying
    @Query("update OrganizerToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.expiresAt > :now")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);
}
