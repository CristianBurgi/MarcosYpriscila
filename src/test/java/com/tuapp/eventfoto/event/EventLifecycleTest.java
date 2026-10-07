package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.common.exception.StorageException;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.GuestQuota;
import com.tuapp.eventfoto.photo.GuestQuotaRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.photo.PhotoUploadClaim;
import com.tuapp.eventfoto.photo.PhotoUploadClaimRepository;
import com.tuapp.eventfoto.storage.StorageService;
import com.tuapp.eventfoto.testsupport.MutableClock;
import com.tuapp.eventfoto.testsupport.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.6: job diario de ciclo de vida, con fechas simuladas (reloj fijo) y storage local real (archivos en uploads/).
 * Evento A el 01/11/2026: se borra el 01/12 y el recordatorio sale desde el 26/11.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class EventLifecycleTest {

    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 11, 1);
    private static final LocalDate DELETION = LocalDate.of(2026, 12, 1);

    @Autowired private EventLifecycleService lifecycle;
    @Autowired private MutableClock clock;
    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private PhotoUploadClaimRepository claimRepository;
    @SpyBean private StorageService storageService;
    @SpyBean private EmailService emailService;

    private Event eventA;
    private Event neighbour;

    @BeforeEach
    void setUp() throws IOException {
        cleanDatabase();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("organizadora@test.com").build());
        eventA = withContent(eventRepository.save(TestEvents.base(organizer, "evento-a-k7m2xq9p").eventDate(EVENT_DATE).build()));
        // El de al lado: misma semana, pero se borra un día después.
        neighbour = withContent(eventRepository.save(TestEvents.base(organizer, "evento-b-k7m2xq9p").eventDate(EVENT_DATE.plusDays(1)).build()));
        reset(storageService, emailService);
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteDir(eventA.getId());
        deleteDir(neighbour.getId());
        cleanDatabase();
    }

    private void cleanDatabase() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        claimRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    /** 2 fotos (con comentario), un mensaje, un cupo, un claim, la imagen de fondo y un huérfano (PUT sin /confirm). */
    private Event withContent(Event event) throws IOException {
        String prefix = "events/" + event.getId() + "/";
        for (int i = 0; i < 2; i++) {
            String key = prefix + UUID.randomUUID() + ".jpg";
            writeFile(key);
            Photo photo = photoRepository.save(Photo.builder().event(event).storageKey(key).uploadKey(key).build());
            commentRepository.save(Comment.builder().photo(photo).authorName("Tía").text("Hermosa").build());
            claimRepository.save(PhotoUploadClaim.builder().uploadKey(key).claimedAt(Instant.now()).build());
        }
        messageRepository.save(Message.builder().event(event).authorName("Tío").text("Felicidades").build());
        guestQuotaRepository.save(GuestQuota.builder().event(event).guestToken("token-" + event.getId()).photosUploaded(2).build());
        writeFile(prefix + UUID.randomUUID() + ".jpg"); // huérfano
        String background = prefix + "branding/" + UUID.randomUUID() + ".jpg";
        writeFile(background);
        event.setBackgroundImageKey(background);
        return eventRepository.save(event);
    }

    private static void writeFile(String key) throws IOException {
        Path path = Path.of("uploads", key);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    private static Path dir(UUID eventId) {
        return Path.of("uploads", "events", eventId.toString());
    }

    private static void deleteDir(UUID eventId) throws IOException {
        if (Files.exists(dir(eventId))) {
            try (var walk = Files.walk(dir(eventId))) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    private EventLifecycleService.RunResult runOn(LocalDate argentinaDay) {
        clock.setArgentina(argentinaDay.atTime(3, 0));
        return lifecycle.run("test").orElseThrow();
    }

    private void assertContentIntact(Event event) throws IOException {
        try (var files = Files.walk(dir(event.getId()))) {
            assertThat(files.filter(Files::isRegularFile).count()).as("archivos de " + event.getSlug()).isEqualTo(4);
        }
        assertThat(photoRepository.findByEventId(event.getId())).hasSize(2);
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getPurgedAt()).isNull();
    }

    @Test
    @DisplayName("Borrado −6 días: nada. −5: un mail. Otra corrida: ningún mail más. Fecha de borrado: todo borrado y el vecino intacto. Otra corrida: sin efectos")
    void fullLifecycle() throws Exception {
        assertThat(runOn(DELETION.minusDays(6))).isEqualTo(new EventLifecycleService.RunResult(0, 0, 0));
        verify(emailService, never()).send(any());

        assertThat(runOn(DELETION.minusDays(5))).isEqualTo(new EventLifecycleService.RunResult(1, 0, 0));
        ArgumentCaptor<EmailService.EmailMessage> mail = ArgumentCaptor.forClass(EmailService.EmailMessage.class);
        verify(emailService, times(1)).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("organizadora@test.com");
        assertThat(mail.getValue().subject()).isEqualTo("Tu álbum se borra el martes 1 de diciembre");
        assertThat(mail.getValue().htmlBody()).contains("martes 1 de diciembre", "álbum completo (ZIP)", "libro de visitas (PDF)", "/admin/login");
        assertThat(eventRepository.findById(eventA.getId()).orElseThrow().getExpiryReminderSentAt()).isNotNull();

        // Misma corrida repetida, y el día siguiente (todavía antes del borrado): no se reenvía. El vecino recibe el suyo.
        assertThat(runOn(DELETION.minusDays(5))).isEqualTo(new EventLifecycleService.RunResult(0, 0, 0));
        assertThat(runOn(DELETION.minusDays(4))).isEqualTo(new EventLifecycleService.RunResult(1, 0, 0));
        verify(emailService, times(2)).send(any());

        // Vencido antes de que corra el job: ya no se sirve, pero el job lo purga igual.
        clock.setArgentina(DELETION.atStartOfDay());
        mockMvc.perform(get("/e/" + eventA.getSlug())).andExpect(status().isGone());
        assertThat(runOn(DELETION)).isEqualTo(new EventLifecycleService.RunResult(0, 1, 0));

        assertThat(dir(eventA.getId())).doesNotExist(); // fotos, fondo y huérfano
        assertThat(photoRepository.findByEventId(eventA.getId())).isEmpty();
        assertThat(messageRepository.count()).isEqualTo(1); // el del vecino
        assertThat(commentRepository.count()).isEqualTo(2);
        assertThat(guestQuotaRepository.count()).isEqualTo(1);
        assertThat(claimRepository.count()).isEqualTo(2);
        Event purged = eventRepository.findById(eventA.getId()).orElseThrow();
        assertThat(purged.getPurgedAt()).isNotNull();
        assertThat(purged.getBackgroundImageKey()).isNull();
        assertContentIntact(neighbour);

        clearInvocations(storageService, emailService);
        assertThat(runOn(DELETION)).isEqualTo(new EventLifecycleService.RunResult(0, 0, 0));
        verify(storageService, never()).deleteEventObjects(any());
        verify(emailService, never()).send(any());
    }

    @Test
    @DisplayName("Si el job no corrió el día del recordatorio, sale en la siguiente corrida; si ya llegó la fecha de borrado, se borra sin mail")
    void lateRuns() {
        assertThat(runOn(DELETION.minusDays(2))).isEqualTo(new EventLifecycleService.RunResult(2, 0, 0));
        reset(emailService);
        eventRepository.findById(eventA.getId()).ifPresent(e -> {
            e.setExpiryReminderSentAt(null);
            eventRepository.save(e);
        });
        assertThat(runOn(DELETION.plusDays(10))).isEqualTo(new EventLifecycleService.RunResult(0, 2, 0));
        verify(emailService, never()).send(any());
    }

    @Test
    @DisplayName("retention_override_until se respeta: ni recordatorio ni borrado hasta esa fecha")
    void retentionOverride() throws Exception {
        LocalDate override = LocalDate.of(2027, 1, 31);
        Event event = eventRepository.findById(eventA.getId()).orElseThrow();
        event.setRetentionOverrideUntil(override);
        eventRepository.save(event);

        assertThat(runOn(DELETION)).isEqualTo(new EventLifecycleService.RunResult(1, 0, 0)); // solo el recordatorio del vecino
        assertContentIntact(eventA);
        mockMvc.perform(get("/e/" + eventA.getSlug())).andExpect(status().isOk());

        // El recordatorio de A sale 5 días antes de SU fecha; el vecino ya venció y se borra en esa misma corrida.
        assertThat(runOn(override.minusDays(6))).isEqualTo(new EventLifecycleService.RunResult(0, 1, 0));
        assertThat(runOn(override.minusDays(5))).isEqualTo(new EventLifecycleService.RunResult(1, 0, 0));
        assertThat(runOn(override)).isEqualTo(new EventLifecycleService.RunResult(0, 1, 0));
        assertThat(dir(eventA.getId())).doesNotExist();
    }

    @Test
    @DisplayName("Fallo parcial: si storage falla en un evento, su base queda intacta, el resto se procesa y la próxima corrida lo completa")
    void storageFailureLeavesDatabaseIntact() throws Exception {
        doThrow(new StorageException("R2 caído")).when(storageService).deleteEventObjects(eventA.getId());

        assertThat(runOn(DELETION.plusDays(1))).isEqualTo(new EventLifecycleService.RunResult(0, 1, 1));
        assertContentIntact(eventA);
        assertThat(eventRepository.findById(neighbour.getId()).orElseThrow().getPurgedAt()).isNotNull();

        reset(storageService);
        assertThat(runOn(DELETION.plusDays(2))).isEqualTo(new EventLifecycleService.RunResult(0, 1, 0));
        assertThat(dir(eventA.getId())).doesNotExist();
        assertThat(photoRepository.count()).isZero();
    }

    @Test
    @DisplayName("POST /api/v1/superadmin/lifecycle/run corre el mismo proceso, solo para el superadmin")
    void manualTrigger() throws Exception {
        clock.setArgentina(DELETION.atTime(10, 0));
        mockMvc.perform(post("/api/v1/superadmin/lifecycle/run").with(user("organizador").roles("ORGANIZER")))
                .andExpect(status().isForbidden());
        assertThat(eventRepository.findById(eventA.getId()).orElseThrow().getPurgedAt()).isNull();

        mockMvc.perform(post("/api/v1/superadmin/lifecycle/run").with(user("superadmin@boda.com").roles("SUPERADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminders").value(1)) // el vecino: se borra mañana
                .andExpect(jsonPath("$.purged").value(1))
                .andExpect(jsonPath("$.failed").value(0));
        mockMvc.perform(post("/api/v1/superadmin/lifecycle/run").with(user("superadmin@boda.com").roles("SUPERADMIN")))
                .andExpect(jsonPath("$.reminders").value(0))
                .andExpect(jsonPath("$.purged").value(0));
    }
}
