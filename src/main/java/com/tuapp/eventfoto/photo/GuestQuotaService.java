package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.exception.GuestQuotaExceededException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.photo.dto.GuestPhotoLimitDTO;
import com.tuapp.eventfoto.photo.dto.GuestQuotaResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Límite de fotos por invitado y toda la lógica de lectura/incremento del cupo.
 *
 * El límite es POR EVENTO ({@link Event#getMaxPhotosPerGuest()}; {@code null} = sin límite). El único número
 * que vive en el código es el default de los eventos nuevos, que sale de la configuración
 * ({@code app.guest-quota.default-max-photos-per-guest}).
 *
 * El contador es monotónico: solo sube. Borrar una foto en admin NO le devuelve el cupo al invitado, a
 * propósito (ver PhotoServiceImpl.deletePhoto, que no toca GuestQuota). Sin límite se sigue contando igual,
 * para que volver a un límite numérico a mitad del evento sea coherente.
 *
 * El guestToken lo elige el cliente, así que este límite es una regla de cortesía, no un control de
 * seguridad: los frenos reales son el rate limit por IP, el tope de tamaño por archivo y el tope total de
 * fotos por evento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestQuotaService {

    private final GuestQuotaRepository guestQuotaRepository;
    private final EventRepository eventRepository;

    /** Default de los eventos NUEVOS (application.yml; ahí también está el fallback a la propiedad vieja). */
    @Value("${app.guest-quota.default-max-photos-per-guest}")
    private int defaultMaxPhotosPerGuest;

    public int getDefaultMaxPhotosPerGuest() {
        return defaultMaxPhotosPerGuest;
    }

    /** Límite del evento; {@code null} = sin límite. */
    public Integer getMaxPhotosPerGuest(Event event) {
        return event.getMaxPhotosPerGuest();
    }

    /** Fotos que le quedan al invitado en el evento; {@code null} = sin límite. */
    @Transactional(readOnly = true)
    public Integer getRemainingPhotos(Event event, String guestToken) {
        Integer max = getMaxPhotosPerGuest(event);
        if (max == null) {
            return null;
        }
        int used = guestQuotaRepository.findByEventIdAndGuestToken(event.getId(), guestToken)
                .map(GuestQuota::getPhotosUploaded)
                .orElse(0);
        return Math.max(0, max - used);
    }

    @Transactional(readOnly = true)
    public GuestQuotaResponseDTO getQuota(Event event, String guestToken) {
        Integer max = getMaxPhotosPerGuest(event);
        if (max == null) {
            return GuestQuotaResponseDTO.unlimitedQuota();
        }
        return GuestQuotaResponseDTO.limited(max, getRemainingPhotos(event, guestToken));
    }

    /**
     * Chequeo previo (NO atómico) antes de generar la presigned URL o de subir bytes en upload-direct:
     * falla rápido si ya sabemos que no hay cupo, evitando gastar una presigned URL o una subida a storage
     * que después se rechazaría igual. Usa el límite del {@code Event} recién cargado por el caller; la
     * verdad final y atómica la determina incrementUsageOrThrow(), que lee el límite vigente de la base.
     */
    public void assertQuotaAvailable(Event event, String guestToken) {
        Integer remaining = getRemainingPhotos(event, guestToken);
        if (remaining != null && remaining <= 0) {
            throw new GuestQuotaExceededException(quotaExceededMessage(getMaxPhotosPerGuest(event)));
        }
    }

    /**
     * Incremento atómico a nivel de BD del cupo del invitado. Si la fila todavía no existe para este
     * (event, guestToken), la crea primero. Si el UPDATE atómico no afecta ninguna fila, el cupo ya estaba
     * agotado -- incluso bajo una condición de carrera entre dos pestañas del mismo invitado -- y se
     * rechaza sin permitir pasarse del límite. El límite que se aplica es el vigente en la base (ver
     * {@link GuestQuotaRepository#incrementIfAllowed}), no el de la copia del Event que traiga el caller.
     */
    @Transactional
    public void incrementUsageOrThrow(Event event, String guestToken) {
        ensureQuotaRowExists(event, guestToken);

        int updatedRows = guestQuotaRepository.incrementIfAllowed(event.getId(), guestToken);
        if (updatedRows == 0) {
            log.warn("Cupo de fotos agotado para invitado (evento '{}', token '{}')", event.getId(), guestToken);
            Integer currentMax = eventRepository.findById(event.getId()).map(Event::getMaxPhotosPerGuest).orElse(null);
            throw new GuestQuotaExceededException(quotaExceededMessage(currentMax));
        }
    }

    /**
     * Cambia el límite del evento entre "el default configurado" y "sin límite". Devuelve el estado nuevo.
     * Puede hacerse con el evento en curso: no toca los contadores de nadie.
     */
    @Transactional
    public GuestPhotoLimitDTO updateGuestPhotoLimit(UUID eventId, boolean unlimited) {
        Integer value = unlimited ? null : defaultMaxPhotosPerGuest;
        eventRepository.updateMaxPhotosPerGuest(eventId, value);
        log.info("Límite de fotos por invitado del evento {} cambiado a: {}", eventId, unlimited ? "sin límite" : value);
        return new GuestPhotoLimitDTO(unlimited, value);
    }

    private void ensureQuotaRowExists(Event event, String guestToken) {
        guestQuotaRepository.insertIfAbsent(UUID.randomUUID(), event.getId(), guestToken);
    }

    private String quotaExceededMessage(Integer max) {
        if (max == null) {
            return "Ya usaste tus fotos para este evento.";
        }
        return String.format("Ya usaste tus %d fotos para este evento.", max);
    }
}
