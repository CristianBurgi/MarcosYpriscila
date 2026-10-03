package com.tuapp.eventfoto.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GuestQuotaRepository extends JpaRepository<GuestQuota, UUID> {

    /**
     * Crea la fila del invitado si todavía no existe; si otra request concurrente la creó justo antes, no hace nada.
     * Tiene que ser un INSERT ... ON CONFLICT DO NOTHING en la base: con save() + catch de
     * DataIntegrityViolationException, el error de unicidad salta en el flush (fuera del try) y en Postgres además
     * deja abortada la transacción entera, así que dos pestañas de un invitado nuevo daban un 500.
     */
    @Modifying
    @Query(value = "INSERT INTO guest_quotas (id, event_id, guest_token, photos_uploaded, created_at) " +
                   "VALUES (:id, :eventId, :guestToken, 0, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId, @Param("guestToken") String guestToken);

    Optional<GuestQuota> findByEventIdAndGuestToken(UUID eventId, String guestToken);

    /**
     * Incremento atómico a nivel de BD, en UN solo UPDATE que también lee el límite vigente del evento
     * (subconsulta a events): solo incrementa si el evento no tiene límite (NULL) o el contador todavía
     * está por debajo. Devuelve las filas afectadas (0 o 1) para que el caller sepa si el incremento ocurrió
     * o si el cupo estaba agotado. Evita el patrón leer-modificar-escribir en Java (carrera entre pestañas
     * del mismo invitado) y evita aplicar un límite viejo si el organizador lo cambió mientras la subida
     * estaba en curso: el límite que cuenta es el que hay en la base en este instante.
     */
    @Modifying
    @Query("UPDATE GuestQuota g SET g.photosUploaded = g.photosUploaded + 1 " +
           "WHERE g.event.id = :eventId AND g.guestToken = :guestToken AND (" +
           "(SELECT e.maxPhotosPerGuest FROM Event e WHERE e.id = :eventId) IS NULL " +
           "OR g.photosUploaded < (SELECT e.maxPhotosPerGuest FROM Event e WHERE e.id = :eventId))")
    int incrementIfAllowed(@Param("eventId") UUID eventId, @Param("guestToken") String guestToken);
}
