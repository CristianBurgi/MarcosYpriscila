package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.moderation.ModeratorStreamRegistry;
import com.tuapp.eventfoto.storage.StorageKeys;
import com.tuapp.eventfoto.storage.StorageService;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ciclo de vida del álbum (Fase 9.6), una vez por día y a pedido del superadmin:
 * <ol>
 *   <li>Recordatorio, desde {@link UploadWindow#REMINDER_DAYS_BEFORE} días antes de la fecha de borrado: un mail por
 *       evento, una sola vez (reserva en expiry_reminder_sent_at, liberada si el envío falla). Si la corrida de ese
 *       día no ocurrió, sale en la siguiente; si ya llegó la fecha de borrado, no tiene sentido y no sale.</li>
 *   <li>Borrado, con la fecha de borrado alcanzada: R2 primero (todo events/{id}/, fondo y huérfanos incluidos), después
 *       la base en una transacción corta. La fila del evento queda con purged_at.</li>
 * </ol>
 * Cada evento por separado: uno que falla (log ERROR + Sentry) no frena a los demás y se reintenta en la próxima
 * corrida. Correrlo dos veces no tiene efectos: un evento ya borrado no es candidato y la reserva no se repite.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventLifecycleService {

    private final EventRepository eventRepository;
    private final UploadWindow uploadWindow;
    private final StorageService storageService;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final EmailService emailService;
    private final ITemplateEngine templateEngine;
    private final AppUrls appUrls;
    private final ModeratorStreamRegistry moderatorStreamRegistry;
    private final Clock clock;

    // ponytail: Railway corre una sola réplica, así que alcanza un flag en memoria para que el cron y el disparo
    // manual no se pisen. Con más de una réplica hace falta un lock en la base (por ejemplo pg_try_advisory_lock).
    private final AtomicBoolean running = new AtomicBoolean();

    public record RunResult(int reminders, int purged, int failed) {
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "America/Argentina/Buenos_Aires")
    public void scheduledRun() {
        run("cron");
    }

    /** @return vacío si ya había una corrida en curso (no se encola: la próxima la hace igual). */
    public Optional<RunResult> run(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Ciclo de vida: corrida pedida por {} descartada, ya hay una en curso", trigger);
            return Optional.empty();
        }
        try {
            RunResult result = runOnce();
            log.info("Ciclo de vida ({}): {} recordatorio(s), {} álbum(es) borrado(s), {} evento(s) con error",
                    trigger, result.reminders(), result.purged(), result.failed());
            return Optional.of(result);
        } finally {
            running.set(false);
        }
    }

    private RunResult runOnce() {
        LocalDate today = uploadWindow.today();
        int reminders = 0;
        int purged = 0;
        int failed = 0;
        // ponytail: recorre todos los eventos sin borrar (la regla vive en un solo lugar, UploadWindow); pasarlo a un
        // WHERE con fechas cuando haya miles.
        for (Event event : eventRepository.findLifecycleCandidates()) {
            LocalDate deletion = uploadWindow.deletionDate(event);
            try {
                if (!today.isBefore(deletion)) {
                    purge(event.getId());
                    purged++;
                } else if (!today.isBefore(deletion.minusDays(UploadWindow.REMINDER_DAYS_BEFORE))
                        && event.getExpiryReminderSentAt() == null && sendReminder(event, deletion)) {
                    reminders++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Ciclo de vida: falló el evento {}; se reintenta en la próxima corrida", event.getId(), e);
                Sentry.captureException(e);
            }
        }
        return new RunResult(reminders, purged, failed);
    }

    private boolean sendReminder(Event event, LocalDate deletion) {
        if (eventRepository.reserveExpiryReminder(event.getId(), clock.instant()) == 0) {
            return false;
        }
        try {
            Context context = new Context();
            context.setVariable("eventName", event.getName());
            context.setVariable("deletionDay", UploadWindow.day(deletion));
            context.setVariable("loginUrl", appUrls.loginUrl());
            String html = templateEngine.process("email/expiry-reminder", context);
            emailService.send(new EmailService.EmailMessage(event.getOrganizer().getEmail(),
                    "Tu álbum se borra el " + UploadWindow.day(deletion), html, "expiry-reminder:" + event.getId()));
        } catch (RuntimeException e) {
            eventRepository.releaseExpiryReminder(event.getId());
            throw e;
        }
        log.info("Ciclo de vida: recordatorio de borrado del evento {} (se borra el {})", event.getId(), deletion);
        return true;
    }

    private void purge(UUID eventId) {
        // 1. Storage primero, sin transacción ni conexión de base: si falla, la base queda intacta y se reintenta.
        int objects = storageService.deleteEventObjects(eventId);

        // 2. La base, en una transacción corta. Hijos antes que padres; photo_upload_claims no tiene event_id: va por
        // el prefijo de su upload_key.
        int[] counts = new TransactionTemplate(transactionManager).execute(status -> {
            int comments = jdbcTemplate.update("DELETE FROM comments WHERE photo_id IN (SELECT id FROM photos WHERE event_id = ?)", eventId);
            int photos = jdbcTemplate.update("DELETE FROM photos WHERE event_id = ?", eventId);
            int messages = jdbcTemplate.update("DELETE FROM messages WHERE event_id = ?", eventId);
            jdbcTemplate.update("DELETE FROM guest_quotas WHERE event_id = ?", eventId);
            jdbcTemplate.update("DELETE FROM photo_upload_claims WHERE upload_key LIKE ?", StorageKeys.prefixOf(eventId) + "%");
            jdbcTemplate.update("UPDATE events SET purged_at = CURRENT_TIMESTAMP, background_image_key = NULL WHERE id = ? AND purged_at IS NULL", eventId);
            return new int[]{photos, messages, comments};
        });

        // 3. El link de moderador ya da 404 (ModeratorTokenPolicy); se cierran los streams que quedaron abiertos.
        moderatorStreamRegistry.closeAll(eventId);
        log.info("Ciclo de vida: álbum del evento {} borrado -- storage: {} objeto(s), fotos: {}, mensajes: {}, comentarios: {}",
                eventId, objects, counts[0], counts[1], counts[2]);
    }
}
