package com.tuapp.eventfoto.photo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Page<Photo> findByEventIdOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    List<Photo> findByEventId(UUID eventId);

    List<Photo> findByEventIdAndIdIn(UUID eventId, List<UUID> ids);

    long countByEventId(UUID eventId);

    /** Busqueda acotada al evento: una foto de otro evento es "no encontrada" (IDOR). */
    Optional<Photo> findByIdAndEventId(UUID id, UUID eventId);

    boolean existsByIdAndEventId(UUID id, UUID eventId);

    /** Acotado al evento: la idempotencia de /confirm nunca devuelve la foto de otro evento. */
    Optional<Photo> findByUploadKeyAndEventId(String uploadKey, UUID eventId);
}
