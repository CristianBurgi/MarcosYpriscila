package com.tuapp.eventfoto.checkout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface PendingPurchaseRepository extends JpaRepository<PendingPurchase, UUID> {

    @Modifying
    @Transactional
    @Query("update PendingPurchase p set p.mpPreferenceId = :preferenceId where p.id = :id")
    int setPreferenceId(@Param("id") UUID id, @Param("preferenceId") String preferenceId);

    /**
     * Descarta solo lo que NO se puede cobrar ya: sin procesar, sin pago de MP asociado, y (a) vencida con margen o
     * (b) sin preferencia después del plazo de gracia (MP falló y la limpieza inmediata tampoco pudo borrarla).
     * Una fila con processed_at o mp_payment_id no se toca nunca.
     */
    @Modifying
    @Transactional
    @Query("""
            delete from PendingPurchase p
             where p.processedAt is null and p.mpPaymentId is null
               and ((p.mpPreferenceId is null and p.createdAt < :orphanCutoff) or p.createdAt < :expiredCutoff)
            """)
    int discard(@Param("orphanCutoff") Instant orphanCutoff, @Param("expiredCutoff") Instant expiredCutoff);
}
