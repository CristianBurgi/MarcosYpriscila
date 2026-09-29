package com.tuapp.eventfoto.organizer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizerTokenRepository extends JpaRepository<OrganizerToken, UUID> {

    Optional<OrganizerToken> findByTokenHash(String tokenHash);

    List<OrganizerToken> findByOrganizerIdAndPurposeAndUsedAtIsNull(UUID organizerId, OrganizerTokenPurpose purpose);
}
