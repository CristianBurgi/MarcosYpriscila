package com.tuapp.eventfoto.photo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Reclamo atómico y barato de una upload_key ANTES de tocar storage/HEIC/cupo
 * (Fase 9.0 - Bloque C): el frontend puede reintentar POST /confirm con la MISMA
 * upload_key ante un fallo de red (el PUT a R2 ya se hizo, solo /confirm no llegó a
 * responder), y dos llamadas casi simultáneas con la misma key deben distinguirse ANTES
 * de que ambas hagan el trabajo pesado (conversión HEIC).
 *
 * Cada método va en su PROPIA transacción corta (REQUIRES_NEW) en un bean separado,
 * invocado a través del proxy de Spring -- nunca como this.metodo() dentro de la misma
 * clase que PhotoServiceImpl.confirmUpload, donde la anotación no tendría efecto (self
 * invocation no pasa por el proxy). Esto es deliberado, no un detalle de estilo: si el
 * insert de la reclamación quedara dentro de una transacción más larga que después hace
 * la conversión HEIC, Postgres bajo READ COMMITTED bloquearía al segundo hilo en el lock
 * de esa fila hasta que la primera transacción haga commit -- exactamente la latencia
 * (varios segundos de conversión HEIC) que queremos evitarle al perdedor de la carrera,
 * y con varios celulares reintentando subidas lentas a la vez, la forma de agotar el pool
 * de conexiones (el mismo que ya hubo que subir de 10 a 20 en la Fase 8). Con una
 * transacción propia y corta, el lock se libera casi de inmediato.
 *
 * La excepción de violación de unicidad se atrapa AFUERA de estos métodos (en el
 * caller, PhotoServiceImpl), nunca adentro: una vez que la base aborta una transacción
 * por un error de SQL, ese mismo EntityManager/sesión ya no es seguro de seguir usando
 * dentro de esa misma transacción. Dejar que la excepción se propague fuera del proxy de
 * Spring garantiza un ROLLBACK limpio de ESTA transacción antes de que el caller la
 * atrape en un contexto sin transacción abierta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoUploadClaimService {

    private final PhotoUploadClaimRepository claimRepository;

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException si la key ya estaba
     *         reclamada (reintento propio o una carrera concurrente real). El caller debe
     *         atrapar esa excepción específica, nunca dentro de este método.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claimOrThrow(String uploadKey) {
        // PhotoUploadClaim implementa Persistable (ver esa clase): así save() hace un
        // persist() real (INSERT) en vez de un merge() silencioso, y la violación de
        // unicidad llega acá ya traducida a DataIntegrityViolationException por el proxy
        // de Spring Data del repositorio (no pasaría si se llamara a un EntityManager
        // inyectado directamente, sin pasar por un bean @Repository).
        claimRepository.saveAndFlush(PhotoUploadClaim.builder().uploadKey(uploadKey).claimedAt(Instant.now()).build());
    }

    /**
     * Registra el rechazo definitivo de esta upload_key (archivo inválido, cupo agotado)
     * para que un duplicado (reintento propio o carrera concurrente) reciba el MISMO
     * status y mensaje en vez de un 503 genérico que lo haría reintentar para siempre.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String uploadKey, int httpStatus, String message) {
        claimRepository.findById(uploadKey).ifPresent(claim -> {
            claim.setFailedStatus(httpStatus);
            claim.setFailedMessage(message);
            claimRepository.save(claim);
        });
    }

    /**
     * Libera la key tras un error transitorio (R2/BD caídos un instante) para que el
     * reintento del invitado vuelva a procesarla en vez de recibir un "falló" permanente.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String uploadKey) {
        claimRepository.deleteById(uploadKey);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PhotoUploadClaim> find(String uploadKey) {
        return claimRepository.findById(uploadKey);
    }
}
