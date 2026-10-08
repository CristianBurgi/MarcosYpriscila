package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.checkout.PaymentIncidentRepository;
import com.tuapp.eventfoto.checkout.PendingPurchase;
import com.tuapp.eventfoto.checkout.PendingPurchaseRepository;
import com.tuapp.eventfoto.checkout.PurchaseConfirmationMailer;
import com.tuapp.eventfoto.checkout.PurchaseConfirmationService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.superadmin.dto.PanelEventRow;
import com.tuapp.eventfoto.superadmin.dto.PanelIncidentRow;
import com.tuapp.eventfoto.superadmin.dto.PanelPurchaseRow;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Panel del superadmin (9.7-A): el listado de toda la plataforma y las acciones manuales. Las acciones de pago reusan
 * las piezas de la 9.4 (confirmPayment, el mailer) y solo existen con el checkout prendido: apagado, 409.
 */
@Service
@RequiredArgsConstructor
public class SuperadminPanelService {

    public static final int PAGE_SIZE = 50;

    public enum Scope { ACTIVE, EXPIRED, ALL }

    public record EventView(PanelEventRow event, PanelStatus status, long photos, long messages,
                            LocalDate deletionDate, boolean purchased) {
    }

    public record EventsPage(List<EventView> events, int page, boolean hasNext) {
    }

    public record RetentionResult(LocalDate deletionDate, boolean reminderReset) {
    }

    private final SuperadminPanelQueries queries;
    private final EventRepository eventRepository;
    private final OrganizerRepository organizerRepository;
    private final PendingPurchaseRepository purchaseRepository;
    private final PaymentIncidentRepository incidentRepository;
    private final ObjectProvider<PurchaseConfirmationService> confirmationService;
    private final ObjectProvider<PurchaseConfirmationMailer> confirmationMailer;
    private final UploadWindow uploadWindow;
    private final Clock clock;

    public LocalDate today() {
        return uploadWindow.today();
    }

    // ---------- listado: cantidad fija de consultas, sin importar cuántos eventos haya ----------

    @Transactional(readOnly = true)
    public EventsPage events(Scope scope, EventOrigin origin, int page) {
        LocalDate today = uploadWindow.today();
        // Uno de más para saber si hay página siguiente, sin un COUNT aparte.
        List<PanelEventRow> rows = queries.findEvents(scope.name(), origin, today,
                today.minusDays(UploadWindow.RETENTION_DAYS), PageRequest.of(page, PAGE_SIZE + 1));
        boolean hasNext = rows.size() > PAGE_SIZE;
        if (hasNext) {
            rows = rows.subList(0, PAGE_SIZE);
        }
        if (rows.isEmpty()) {
            return new EventsPage(List.of(), page, false);
        }
        List<UUID> ids = rows.stream().map(PanelEventRow::id).toList();
        Map<UUID, Long> photos = toMap(queries.countPhotos(ids));
        Map<UUID, Long> messages = toMap(queries.countMessages(ids));
        Set<UUID> purchased = new HashSet<>(queries.findPurchasedEventIds(ids));
        return new EventsPage(rows.stream().map(row -> {
            Event event = asEvent(row);
            return new EventView(row, PanelStatus.of(event, uploadWindow), photos.getOrDefault(row.id(), 0L),
                    messages.getOrDefault(row.id(), 0L), uploadWindow.deletionDate(event), purchased.contains(row.id()));
        }).toList(), page, hasNext);
    }

    @Transactional(readOnly = true)
    public List<PanelPurchaseRow> pendingPurchases() {
        return queries.findPendingPurchases();
    }

    @Transactional(readOnly = true)
    public List<PanelIncidentRow> openIncidents() {
        return queries.findOpenIncidents();
    }

    /** Solo lo que UploadWindow necesita para calcular estado y fecha de borrado. */
    private static Event asEvent(PanelEventRow row) {
        return Event.builder().eventDate(row.eventDate()).isActive(row.active()).wizardCompletedAt(row.wizardCompletedAt())
                .retentionOverrideUntil(row.retentionOverrideUntil()).purgedAt(row.purgedAt()).build();
    }

    private static Map<UUID, Long> toMap(List<Object[]> counts) {
        Map<UUID, Long> map = new HashMap<>();
        counts.forEach(c -> map.put((UUID) c[0], (Long) c[1]));
        return map;
    }

    // ---------- acciones ----------

    /**
     * Fija la fecha de borrado: entre hoy y un año después de la original (fecha del evento + 30 días). Si el
     * recordatorio ya había salido se reinicia; un evento vencido sin borrar vuelve a estar disponible (el
     * vencimiento se calcula al vuelo con esta fecha).
     */
    @Transactional
    public RetentionResult overrideRetention(UUID eventId, LocalDate until) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe ese evento."));
        if (event.getPurgedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El álbum ya se borró: no se puede extender.");
        }
        if (event.getEventDate() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El evento no tiene fecha: todavía no vence.");
        }
        LocalDate max = event.getEventDate().plusDays(UploadWindow.RETENTION_DAYS).plusYears(1);
        if (until == null || until.isBefore(uploadWindow.today())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha no puede ser anterior a hoy.");
        }
        if (until.isAfter(max)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha no puede pasar del " + max + " (un año después de la fecha de borrado original).");
        }
        boolean reminderReset = event.getExpiryReminderSentAt() != null;
        // Si el job lo borró entre la lectura y acá, el UPDATE no toca nada.
        if (eventRepository.overrideRetention(eventId, until) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El álbum ya se borró: no se puede extender.");
        }
        return new RetentionResult(until, reminderReset);
    }

    /** @return true si lo resolvió esta llamada, false si ya estaba resuelto. */
    @Transactional
    public boolean resolveIncident(UUID incidentId) {
        if (incidentRepository.resolve(incidentId, clock.instant()) == 1) {
            return true;
        }
        if (incidentRepository.existsById(incidentId)) {
            return false;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe ese incidente.");
    }

    /** La misma puerta que el webhook: consulta el pago real a MP y valida todo. Ningún atajo crea un evento. */
    public PurchaseConfirmationService.Outcome confirmPayment(String paymentId) {
        PurchaseConfirmationService service = confirmationService.getIfAvailable();
        if (service == null) {
            throw checkoutDisabled();
        }
        try {
            return service.confirmPayment(paymentId == null ? null : paymentId.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El payment_id tiene que ser numérico.");
        }
    }

    /** Reenvía "Tu evento está listo" de la compra que creó este evento y vuelve a marcar la reserva. */
    @Transactional
    public String resendConfirmation(UUID eventId) {
        PurchaseConfirmationMailer mailer = confirmationMailer.getIfAvailable();
        if (mailer == null) {
            throw checkoutDisabled();
        }
        PendingPurchase purchase = purchaseRepository.findFirstByEventIdAndProcessedAtIsNotNull(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese evento no salió de una compra."));
        String to = organizerRepository.findById(purchase.getOrganizerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta de esa compra ya no existe."))
                .getEmail();
        mailer.sendEventReady(to, purchase.getEventName(), purchase.isRepurchase(), purchase.getId());
        purchase.setConfirmationEmailSentAt(clock.instant());
        return to;
    }

    private static ResponseStatusException checkoutDisabled() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "El checkout está deshabilitado.");
    }
}
