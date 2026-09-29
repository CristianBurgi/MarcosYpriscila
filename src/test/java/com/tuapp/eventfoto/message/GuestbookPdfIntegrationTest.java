package com.tuapp.eventfoto.message;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 9.0 - Bloque D: el PDF que se le entrega a la pareja. Genera el libro con los
 * mensajes más difíciles (emojis simples y compuestos, tonos de piel, banderas, acentos,
 * ñ, un mensaje de 1000 caracteres, una palabra enorme sin espacios, uno sin autor) y
 * verifica sobre el PDF real, no sobre el HTML.
 *
 * Deja target/libro-de-visitas-prueba.pdf y sus páginas en PNG para revisión visual.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GuestbookPdfIntegrationTest {

    static final String NO_SPACES = "Feliciiiiidades".repeat(20); // 300 caracteres sin un solo espacio

    @Autowired
    private GuestbookPdfService guestbookPdfService;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private MessageRepository messageRepository;

    private Event event;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        eventRepository.findBySlug("libro-prueba").ifPresent(eventRepository::delete);
        organizerRepository.findByEmailIgnoreCase("libro-prueba@test.com").ifPresent(organizerRepository::delete);
        Organizer organizer = organizerRepository.save(Organizer.builder().email("libro-prueba@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("Boda de Marcos y Priscila")
                .slug("libro-prueba")
                .eventDate(LocalDate.parse("2026-09-19"))
                .uploadDeadline(Instant.parse("2026-10-04T23:59:00Z"))
                .isActive(true)
                .origin(EventOrigin.PAID)
                .build());

        Instant t = Instant.parse("2026-09-19T21:05:00Z");
        save("Tía Marta", "¡Qué noche hermosa! Los quiero muchísimo 😘❤️💕🍾🥰🤎🩷", t);
        save("Familia Pérez", "Toda la familia 👨‍👩‍👧 les desea lo mejor 👍🏽 ¡Viva los novios! 🇦🇷 1️⃣", t.plusSeconds(60));
        save("Ñandú Muñoz", "Año, niño, pingüino, acción, corazón: ÁÉÍÓÚ áéíóú ñÑ ¿¡«»“”—…", t.plusSeconds(120));
        save("Largo", ("Querida Priscila y querido Marcos, gracias por dejarnos ser parte de este día tan especial. ")
                .repeat(11).substring(0, 1000), t.plusSeconds(180));
        save("Sin espacios", NO_SPACES, t.plusSeconds(240));
        save("   ", "Un mensaje sin autor, con\nsaltos de línea\n\ny un párrafo aparte.", t.plusSeconds(300));
        save("Otros alfabetos", "Ευτυχία! Счастья! こんにちは", t.plusSeconds(360));
    }

    private void save(String author, String text, Instant createdAt) {
        messageRepository.save(Message.builder().event(event).authorName(author).text(text).createdAt(createdAt).build());
    }

    @Test
    @DisplayName("El PDF tiene todos los mensajes en orden, acentos, ñ, emojis, 'Anónimo' y ningún carácter de reemplazo")
    void pdfContainsEveryMessageWithoutReplacementCharacters() throws IOException {
        byte[] pdf = guestbookPdfService.generate("libro-prueba");
        Path out = Path.of("target", "libro-de-visitas-prueba.pdf");
        Files.write(out, pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            String compact = text.replaceAll("\\s+", "");
            // Portada: la tipografía espaciada y el título partido en dos líneas se comparan sin espacios.
            assertThat(compact).contains("LIBRODEVISITAS", "BodadeMarcosyPriscila", "19deseptiembrede2026", "7mensajesdesusinvitados");
            assertThat(text).contains("Tía Marta", "Ñandú Muñoz", "pingüino", "ÁÉÍÓÚ", "¿¡", "— Anónimo");
            assertThat(text).contains("😘", "🍾", "🩷", "👨", "👩", "👧", "👍");
            // Hora en Argentina, calculada sobre el Instant que devuelve la base (H2 en los tests
            // desplaza el valor según la zona de la JVM; en Postgres/producción es exacto).
            Instant stored = messageRepository.findAll().stream()
                    .filter(m -> m.getAuthorName().equals("Tía Marta")).findFirst().orElseThrow().getCreatedAt();
            assertThat(text).contains(java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(java.time.ZoneId.of("America/Argentina/Buenos_Aires")).format(stored));
            assertThat(compact).contains(NO_SPACES);
            // Ni caracteres de reemplazo ni "#", que es lo que dibuja openhtmltopdf cuando ninguna fuente tiene el glifo.
            assertThat(text).doesNotContain("�").doesNotContain("#");
            // Orden cronológico: Tía Marta (primero) antes que Otros alfabetos (último).
            assertThat(text.indexOf("Tía Marta")).isLessThan(text.indexOf("Otros alfabetos"));
            // Griego y cirílico con Noto Serif; japonés no tiene fuente: se omite, sin tofu.
            assertThat(text).contains("Ευτυχία", "Счастья").doesNotContain("こんにちは");
        }
    }

    @Test
    @DisplayName("Ningún glifo se sale del área imprimible (palabra de 300 caracteres sin espacios, mensaje de 1000)")
    void noGlyphOverflowsThePage() throws IOException {
        byte[] pdf = guestbookPdfService.generate("libro-prueba");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<String> overflows = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String s, List<TextPosition> positions) {
                    float pageWidth = getCurrentPage().getMediaBox().getWidth();
                    for (TextPosition p : positions) {
                        if (p.getXDirAdj() < 0 || p.getXDirAdj() + p.getWidthDirAdj() > pageWidth - 20) {
                            overflows.add("página " + getCurrentPageNo() + ": '" + p.getUnicode() + "' en x=" + p.getXDirAdj());
                        }
                    }
                }
            };
            stripper.getText(doc);
            assertThat(overflows).as("glifos fuera de la página").isEmpty();

            // Páginas en PNG para revisión visual (no afectan el resultado del test).
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                ImageIO.write(renderer.renderImageWithDPI(i, 110), "png", new File("target/libro-de-visitas-prueba-p" + (i + 1) + ".png"));
            }
        }
    }
}
