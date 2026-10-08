package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.superadmin.dto.PanelEventRow;
import com.tuapp.eventfoto.superadmin.dto.PanelIncidentRow;
import com.tuapp.eventfoto.superadmin.dto.PanelPurchaseRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Las consultas del listado del superadmin, todas de cantidad fija: una por tabla, nunca una por evento. Solo lectura
 * y solo proyecciones (ningún hash ni referencia completa sale de acá).
 */
public interface SuperadminPanelQueries extends Repository<Event, UUID> {

    /**
     * Vencido = borrado, o con la fecha de borrado alcanzada (la misma regla que UploadWindow.isExpired, en JPQL:
     * retention_override_until o event_date + 30 días, con {@code cutoff = hoy - 30}). Cada término está protegido
     * contra NULL, así que la negación de "activos" nunca da desconocido.
     */
    @Query("""
            select new com.tuapp.eventfoto.superadmin.dto.PanelEventRow(
                e.id, e.name, e.slug, o.id, o.email,
                case when o.passwordHash is null or o.passwordHash = '' then true else false end,
                e.eventDate, e.origin, e.originReason, e.isActive, e.wizardCompletedAt, e.retentionOverrideUntil,
                e.expiryReminderSentAt, e.purgedAt, e.createdAt)
            from Event e join e.organizer o
            where (:origin is null or e.origin = :origin)
              and (:scope = 'ALL'
                or (:scope = 'EXPIRED' and (e.purgedAt is not null
                    or (e.retentionOverrideUntil is not null and e.retentionOverrideUntil <= :today)
                    or (e.retentionOverrideUntil is null and e.eventDate is not null and e.eventDate <= :cutoff)))
                or (:scope = 'ACTIVE' and e.purgedAt is null
                    and (e.retentionOverrideUntil is null or e.retentionOverrideUntil > :today)
                    and (e.retentionOverrideUntil is not null or e.eventDate is null or e.eventDate > :cutoff)))
            order by e.createdAt desc
            """)
    List<PanelEventRow> findEvents(@Param("scope") String scope, @Param("origin") EventOrigin origin,
                                   @Param("today") LocalDate today, @Param("cutoff") LocalDate cutoff, Pageable pageable);

    @Query("select p.event.id, count(p) from Photo p where p.event.id in :ids group by p.event.id")
    List<Object[]> countPhotos(@Param("ids") Collection<UUID> ids);

    @Query("select m.event.id, count(m) from Message m where m.event.id in :ids group by m.event.id")
    List<Object[]> countMessages(@Param("ids") Collection<UUID> ids);

    /** Eventos que salieron de una compra procesada: los que pueden reenviar el mail de confirmación. */
    @Query("select p.eventId from PendingPurchase p where p.processedAt is not null and p.eventId in :ids")
    List<UUID> findPurchasedEventIds(@Param("ids") Collection<UUID> ids);

    /** Sin procesar: lo que el job ya descartó no está en la tabla. En la recompra, el email es el del organizador. */
    @Query("""
            select new com.tuapp.eventfoto.superadmin.dto.PanelPurchaseRow(
                p.createdAt, coalesce(p.email, o.email), p.eventName, p.amount,
                case when p.mpPreferenceId is null then false else true end, p.mpPaymentId, p.lastPaymentStatus, p.id)
            from PendingPurchase p left join Organizer o on o.id = p.organizerId
            where p.processedAt is null
            order by p.createdAt desc
            """)
    List<PanelPurchaseRow> findPendingPurchases();

    @Query("""
            select new com.tuapp.eventfoto.superadmin.dto.PanelIncidentRow(i.id, i.reason, i.paymentId, i.externalReference, i.createdAt)
            from PaymentIncident i where i.resolvedAt is null order by i.createdAt desc
            """)
    List<PanelIncidentRow> findOpenIncidents();
}
