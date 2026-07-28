package com.tuapp.eventfoto.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByEventIdAndIsApprovedTrueOrderByCreatedAtDesc(UUID eventId);

    Page<Message> findByEventIdAndIsApprovedTrueOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    Page<Message> findByEventIdOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    long countByEventId(UUID eventId);
}
