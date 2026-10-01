package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventService;
import com.tuapp.eventfoto.pdf.PdfRenderService;
import com.tuapp.eventfoto.pdf.PdfTextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Libro de visitas en PDF: el recuerdo que se le entrega al organizador con todos los
 * mensajes que les dejaron sus invitados (Fase 9.0 - Bloque D).
 *
 * Solo mensajes publicados: los que rechaza el filtro de palabras nunca se guardan y los
 * que borra el organizador se eliminan de la base, así que alcanza con is_approved = true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestbookPdfService {

    public static final String FILENAME = "libro-de-visitas.pdf";
    static final String ANONYMOUS = "Anónimo";

    private static final ZoneId EVENT_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter COVER_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES_AR).withZone(EVENT_ZONE);
    private static final DateTimeFormatter MESSAGE_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy · HH:mm", ES_AR).withZone(EVENT_ZONE);

    private final EventService eventService;
    private final MessageRepository messageRepository;
    private final PdfRenderService pdfRenderService;
    private final PdfTextSanitizer sanitizer;
    private final PlatformTransactionManager transactionManager;

    public record GuestbookEntry(String author, String text, String when) {
    }

    /** Lo que el PDF necesita, ya extraído de la base: nada de entidades fuera de la transacción. */
    private record GuestbookData(String eventName, String eventDate, List<GuestbookEntry> entries) {
    }

    /**
     * Sin @Transactional en el método: la lectura va en una transacción corta (TransactionTemplate,
     * no un método @Transactional de este mismo bean: la autoinvocación no pasa por el proxy) y el
     * render del PDF -- que puede tardar segundos -- corre con la transacción ya cerrada, sin
     * sostener una conexión del pool.
     */
    public byte[] generate(String slug) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        GuestbookData data = readOnly.execute(status -> {
            Event event = eventService.getEventEntityBySlug(slug);
            List<GuestbookEntry> loaded = messageRepository.findByEventIdAndIsApprovedTrueOrderByCreatedAtAsc(event.getId())
                    .stream()
                    .map(this::toEntry)
                    .filter(entry -> !entry.text().isEmpty())
                    .toList();
            return new GuestbookData(
                    sanitizer.clean(event.getName()),
                    event.getEventDate() != null ? COVER_DATE.format(event.getEventDate()) : "Fecha a confirmar",
                    loaded);
        });
        List<GuestbookEntry> entries = data.entries();

        byte[] pdf = pdfRenderService.render("pdf/libro-de-visitas", Map.of(
                "eventName", data.eventName(),
                "eventDate", data.eventDate(),
                "entries", entries
        ));
        log.info("Libro de visitas generado para '{}': {} mensajes, {} bytes", slug, entries.size(), pdf.length);
        return pdf;
    }

    private GuestbookEntry toEntry(Message message) {
        String author = sanitizer.clean(message.getAuthorName());
        return new GuestbookEntry(
                author.isEmpty() ? ANONYMOUS : author,
                sanitizer.clean(message.getText()),
                MESSAGE_DATE.format(message.getCreatedAt()));
    }
}
