package com.tuapp.eventfoto.checkout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentIncidentRepository extends JpaRepository<PaymentIncident, UUID> {

    /**
     * Registra el incidente si ese payment_id no tiene uno. INSERT ... ON CONFLICT DO NOTHING en la base (y no
     * save() + catch): una violación de unicidad dejaría abortada la transacción entera en Postgres. Devuelve 1 si lo
     * registró, 0 si ya existía: solo en el primer caso se avisa (log ERROR + Sentry).
     */
    @Modifying
    @Query(value = "INSERT INTO payment_incident (id, payment_id, external_reference, reason, created_at) "
            + "VALUES (:id, :paymentId, :externalReference, :reason, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("paymentId") String paymentId,
                       @Param("externalReference") String externalReference, @Param("reason") String reason);

    boolean existsByExternalReferenceAndResolvedAtIsNull(String externalReference);

    Optional<PaymentIncident> findByPaymentId(String paymentId);
}
