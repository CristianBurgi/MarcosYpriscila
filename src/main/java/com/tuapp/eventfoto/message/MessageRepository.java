package com.tuapp.eventfoto.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByEventIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(UUID eventId);

    /** Mensajes publicados en orden cronológico (libro de visitas en PDF). */
    List<Message> findByEventIdAndIsApprovedTrueOrderByCreatedAtAscIdAsc(UUID eventId);

    Page<Message> findByEventIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(UUID eventId, Pageable pageable);

    Page<Message> findByEventIdOrderByCreatedAtDescIdDesc(UUID eventId, Pageable pageable);

    long countByEventId(UUID eventId);

    Optional<Message> findByIdAndEventId(UUID id, UUID eventId);
}
