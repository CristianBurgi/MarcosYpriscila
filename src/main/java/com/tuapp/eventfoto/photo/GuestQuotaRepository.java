package com.tuapp.eventfoto.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GuestQuotaRepository extends JpaRepository<GuestQuota, UUID> {

    Optional<GuestQuota> findByEventIdAndGuestToken(UUID eventId, String guestToken);

    /**
     * Incremento atómico a nivel de BD: solo aplica el UPDATE si el contador actual
     * todavía está por debajo del límite. Devuelve la cantidad de filas afectadas
     * (0 o 1) para que el caller sepa si el incremento realmente ocurrió o si el
     * cupo ya estaba agotado — evita el patrón leer-modificar-escribir en Java,
     * que sería vulnerable a condiciones de carrera entre dos pestañas del mismo
     * invitado subiendo casi al mismo tiempo.
     */
    @Modifying
    @Query("UPDATE GuestQuota g SET g.photosUploaded = g.photosUploaded + 1 " +
           "WHERE g.event.id = :eventId AND g.guestToken = :guestToken AND g.photosUploaded < :maxPhotos")
    int incrementIfUnderLimit(@Param("eventId") UUID eventId, @Param("guestToken") String guestToken, @Param("maxPhotos") int maxPhotos);
}
