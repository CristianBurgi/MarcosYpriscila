package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.exception.GuestQuotaExceededException;
import com.tuapp.eventfoto.event.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Centraliza el límite de fotos por invitado (MAX_PHOTOS_PER_GUEST, único lugar
 * del código con ese valor) y toda la lógica de lectura/incremento del cupo.
 *
 * El contador es monotónico: solo sube. Rechazar o borrar una foto en admin NO
 * le devuelve el cupo al invitado, a propósito (ver PhotoServiceImpl.rejectPhoto/
 * deletePhoto, que no tocan GuestQuota).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestQuotaService {

    private final GuestQuotaRepository guestQuotaRepository;

    @Value("${app.guest-quota.max-photos-per-guest:24}")
    private int maxPhotosPerGuest;

    public int getMaxPhotosPerGuest() {
        return maxPhotosPerGuest;
    }

    @Transactional(readOnly = true)
    public int getRemainingPhotos(UUID eventId, String guestToken) {
        int used = guestQuotaRepository.findByEventIdAndGuestToken(eventId, guestToken)
                .map(GuestQuota::getPhotosUploaded)
                .orElse(0);
        return Math.max(0, maxPhotosPerGuest - used);
    }

    /**
     * Chequeo previo (NO atómico) antes de generar la presigned URL o de subir
     * bytes en upload-direct: falla rápido si ya sabemos que no hay cupo, evitando
     * gastar una presigned URL o una subida a storage que después se rechazaría igual.
     * La verdad final y atómica la determina incrementUsageOrThrow().
     */
    public void assertQuotaAvailable(UUID eventId, String guestToken) {
        if (getRemainingPhotos(eventId, guestToken) <= 0) {
            throw new GuestQuotaExceededException(quotaExceededMessage());
        }
    }

    /**
     * Incremento atómico a nivel de BD del cupo del invitado. Si la fila todavía
     * no existe para este (event, guestToken), la crea primero. Si el UPDATE
     * atómico no afecta ninguna fila, el cupo ya estaba agotado -- incluso bajo
     * una condición de carrera entre dos pestañas del mismo invitado subiendo casi
     * al mismo tiempo -- y se rechaza sin permitir pasarse del límite.
     */
    @Transactional
    public void incrementUsageOrThrow(Event event, String guestToken) {
        ensureQuotaRowExists(event, guestToken);

        int updatedRows = guestQuotaRepository.incrementIfUnderLimit(event.getId(), guestToken, maxPhotosPerGuest);
        if (updatedRows == 0) {
            log.warn("Cupo de fotos agotado para invitado (evento '{}', token '{}')", event.getId(), guestToken);
            throw new GuestQuotaExceededException(quotaExceededMessage());
        }
    }

    private void ensureQuotaRowExists(Event event, String guestToken) {
        if (guestQuotaRepository.findByEventIdAndGuestToken(event.getId(), guestToken).isPresent()) {
            return;
        }
        try {
            guestQuotaRepository.save(GuestQuota.builder()
                    .event(event)
                    .guestToken(guestToken)
                    .photosUploaded(0)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Otra request concurrente ya creó la fila para este mismo invitado justo antes; no es un error.
            log.debug("Fila de GuestQuota ya creada por una request concurrente (evento '{}', token '{}')", event.getId(), guestToken);
        }
    }

    private String quotaExceededMessage() {
        return String.format("Ya usaste tus %d fotos para este evento.", maxPhotosPerGuest);
    }
}
