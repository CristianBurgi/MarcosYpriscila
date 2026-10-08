package com.tuapp.eventfoto.checkout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PendingPurchaseRepository extends JpaRepository<PendingPurchase, UUID> {

    @Modifying
    @Transactional
    @Query("update PendingPurchase p set p.mpPreferenceId = :preferenceId where p.id = :id")
    int setPreferenceId(@Param("id") UUID id, @Param("preferenceId") String preferenceId);

    /**
     * SELECT ... FOR UPDATE: la confirmación del pago (webhook y regreso del navegador, a la vez) se serializa por
     * compra. Tiene que llamarse dentro de una transacción; el lock dura hasta el commit. Nativa y explícita: con
     * PESSIMISTIC_WRITE el dialecto de Postgres genera FOR NO KEY UPDATE, que H2 (tests) no entiende.
     */
    @Query(value = "SELECT * FROM pending_purchase WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<PendingPurchase> findByIdForUpdate(@Param("id") UUID id);

    /** Último estado de un pago no aprobado; una compra ya procesada no se toca. */
    @Modifying
    @Transactional
    @Query("update PendingPurchase p set p.lastPaymentStatus = :status where p.id = :id and p.processedAt is null")
    int recordPaymentStatus(@Param("id") UUID id, @Param("status") String status);

    /** Libera la reserva del mail si el envío falló después del commit: la próxima confirmación lo reintenta. */
    @Modifying
    @Transactional
    @Query("update PendingPurchase p set p.confirmationEmailSentAt = null where p.id = :id")
    int clearEmailClaim(@Param("id") UUID id);

    /**
     * Descarta solo lo que NO se puede cobrar ya: sin procesar, sin pago de MP asociado, y (a) vencida con margen o
     * (b) sin preferencia después del plazo de gracia (MP falló y la limpieza inmediata tampoco pudo borrarla).
     * Una fila con processed_at o mp_payment_id no se toca nunca (fase 9.4: tampoco una compra con incidente de
     * "email ya registrado", que guarda el mp_payment_id justamente para eso).
     */
    @Modifying
    @Transactional
    @Query("""
            delete from PendingPurchase p
             where p.processedAt is null and p.mpPaymentId is null
               and ((p.mpPreferenceId is null and p.createdAt < :orphanCutoff) or p.createdAt < :expiredCutoff)
            """)
    int discard(@Param("orphanCutoff") Instant orphanCutoff, @Param("expiredCutoff") Instant expiredCutoff);

    /** La compra procesada que creó este evento (reenvío del mail de confirmación desde el superadmin). */
    Optional<PendingPurchase> findFirstByEventIdAndProcessedAtIsNotNull(UUID eventId);
}
