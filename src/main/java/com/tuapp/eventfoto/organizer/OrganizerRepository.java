package com.tuapp.eventfoto.organizer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizerRepository extends JpaRepository<Organizer, UUID> {

    Optional<Organizer> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
