package com.tuapp.eventfoto.photo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Page<Photo> findByEventIdOrderByCreatedAtDescIdDesc(UUID eventId, Pageable pageable);

    List<Photo> findByEventId(UUID eventId);

    List<Photo> findByEventIdAndIdIn(UUID eventId, List<UUID> ids);

    long countByEventId(UUID eventId);

    /** Busqueda acotada al evento: una foto de otro evento es "no encontrada" (IDOR). */
    Optional<Photo> findByIdAndEventId(UUID id, UUID eventId);

    boolean existsByIdAndEventId(UUID id, UUID eventId);

    /** Acotado al evento: la idempotencia de /confirm nunca devuelve la foto de otro evento. */
    Optional<Photo> findByUploadKeyAndEventId(String uploadKey, UUID eventId);

    /** Orden de subida del ZIP por partes: más vieja primero, el id desempata (mismo orden que las consultas de abajo). */
    @Query("select p.id from Photo p where p.event.id = :eventId order by p.createdAt asc, p.id asc")
    List<UUID> findIdsInUploadOrder(@Param("eventId") UUID eventId);

    /** Última parte del ZIP: desde la foto de corte (incluida) hasta el final del álbum. */
    @Query("select p from Photo p where p.event.id = :eventId"
            + " and (p.createdAt > :fromAt or (p.createdAt = :fromAt and p.id >= :fromId))"
            + " order by p.createdAt asc, p.id asc")
    List<Photo> findPartFrom(@Param("eventId") UUID eventId, @Param("fromAt") Instant fromAt, @Param("fromId") UUID fromId);

    /** Parte del ZIP entre dos fotos de corte: la primera incluida, la segunda no (es la primera de la parte siguiente). */
    @Query("select p from Photo p where p.event.id = :eventId"
            + " and (p.createdAt > :fromAt or (p.createdAt = :fromAt and p.id >= :fromId))"
            + " and (p.createdAt < :toAt or (p.createdAt = :toAt and p.id < :toId))"
            + " order by p.createdAt asc, p.id asc")
    List<Photo> findPartBetween(@Param("eventId") UUID eventId, @Param("fromAt") Instant fromAt, @Param("fromId") UUID fromId,
                                @Param("toAt") Instant toAt, @Param("toId") UUID toId);
}
