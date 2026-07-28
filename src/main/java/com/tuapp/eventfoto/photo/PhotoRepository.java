package com.tuapp.eventfoto.photo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Page<Photo> findByEventIdAndIsApprovedTrueOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    Page<Photo> findByEventIdAndIsApprovedFalseOrderByCreatedAtAsc(UUID eventId, Pageable pageable);

    List<Photo> findByEventIdAndIsApprovedFalse(UUID eventId);

    Page<Photo> findByEventIdOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    long countByEventId(UUID eventId);

    long countByEventIdAndIsApprovedFalse(UUID eventId);
}
