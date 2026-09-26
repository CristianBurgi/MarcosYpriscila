package com.tuapp.eventfoto.photo;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Fila-mutex para hacer POST /confirm idempotente (Fase 9.0 - Bloque C). No es un dato de
 * negocio: es un candado barato sobre la upload_key, reclamado ANTES de tocar storage/HEIC/
 * cupo. Ver {@link PhotoUploadClaimService} para el porqué de la transacción separada.
 *
 * Implementa {@link Persistable}: el @Id (upload_key) se asigna a mano, no con
 * @GeneratedValue. Sin esto, Spring Data decide si una entidad es "nueva" mirando si su
 * ID es null -- como acá SIEMPRE lo seteamos antes de guardar, JpaRepository.save() la
 * trataría como "existente" y haría un merge() silencioso (actualiza si ya existe, sin
 * lanzar nada) en vez del INSERT real que necesitamos para detectar con una excepción una
 * reclamación repetida.
 */
@Entity
@Table(name = "photo_upload_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhotoUploadClaim implements Persistable<String> {

    @Id
    @Column(name = "upload_key", length = 512)
    private String uploadKey;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    /** Status HTTP del rechazo definitivo (422, 403...) si el ganador de la carrera falló. */
    @Column(name = "failed_status")
    private Integer failedStatus;

    /** Mensaje exacto del rechazo definitivo, para reconstruírselo al perdedor de la carrera. */
    @Column(name = "failed_message", length = 500)
    private String failedMessage;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public String getId() {
        return uploadKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    void onPersist() {
        if (claimedAt == null) {
            claimedAt = Instant.now();
        }
        isNew = false;
    }

    @PostLoad
    void onLoad() {
        isNew = false;
    }
}
